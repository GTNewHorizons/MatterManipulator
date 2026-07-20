package com.recursive_pineapple.matter_manipulator.common.building;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.oredict.OreDictionary;

import appeng.api.util.AEColor;
import appeng.api.util.DimensionalCoord;
import appeng.tile.networking.TileWirelessBase;

import com.gtnewhorizon.gtnhlib.util.CoordinatePacker;
import com.recursive_pineapple.matter_manipulator.asm.Optional;
import com.recursive_pineapple.matter_manipulator.common.data.WeightedSpecList;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.Location;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.MMConfig;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.MMState;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.MMState.Shape;
import com.recursive_pineapple.matter_manipulator.common.utils.MMUtils;
import com.recursive_pineapple.matter_manipulator.common.utils.Mods.Names;

/**
 * Links AE2 wireless connectors in one region to wireless hubs in another region, distributing them according to
 * the configured strategy. Unlike {@link WirelessLinkFixer}, this creates new links instead of preserving existing
 * ones.
 */
public class WirelessLinker {

    /** AE2's hard limit on how many connectors a single wireless hub can have linked at once. */
    private static final int MAX_LINKS_PER_HUB = 32;

    private static final AEColor[] COLORS = AEColor.values();

    /** Positions (per-dimension) of hubs queued for auto-placement, so a later pass can paint them once placed. */
    private static final Map<Integer, Map<Long, AEColor>> PENDING_HUB_COLORS = new ConcurrentHashMap<>();

    private WirelessLinker() {}

    /** The result of a link pass. */
    public static final class LinkResult {

        public String error;
        public int connectorsFound;
        public int connectorsLinked;
        /** Connectors left unlinked because their color group doesn't have enough hub capacity. */
        public int connectorsUnfit;
        public int hubsUsed;
        public int hubsQueued;
    }

    /**
     * Runs the link operation immediately, without messaging the player. Safe to call repeatedly since it always
     * fully recomputes the pairing from the current world state.
     */
    @Optional(Names.APPLIED_ENERGISTICS2)
    public static LinkResult link(MMState state, World world, EntityPlayer player) {
        LinkResult result = new LinkResult();
        MMConfig cfg = state.config;

        if (!Location.areCompatible(cfg.wirelessA, cfg.wirelessB) || !cfg.wirelessA.isInWorld(world)) {
            result.error = "mm.info.error.wireless_link.no_source";
            return result;
        }

        if (!Location.areCompatible(cfg.hubA, cfg.hubB) || !cfg.hubA.isInWorld(world)) {
            result.error = "mm.info.error.wireless_link.no_target";
            return result;
        }

        paintPendingHubs(world, player);

        for (AEColor color : getActiveColorFilters(cfg)) {
            List<TileWirelessBase> connectors = scanRegion(world, cfg.wirelessA, cfg.wirelessB, false, color);
            if (connectors.isEmpty()) continue;

            result.connectorsFound += connectors.size();

            List<TileWirelessBase> hubs = scanRegion(world, cfg.hubA, cfg.hubB, true, color);

            if (hubs.isEmpty()) {
                result.connectorsUnfit += connectors.size();
                continue;
            }

            Map<TileWirelessBase, TileWirelessBase> desired = new HashMap<>();
            int hubsUsedThisGroup;

            if (cfg.wirelessDistribution == MMState.WirelessDistributionMode.FIXED_PER_HUB) {
                int perHub = MMUtils.clamp(cfg.wirelessCountPerHub, 1, MAX_LINKS_PER_HUB);
                int capacity = hubs.size() * perHub;

                if (capacity < connectors.size()) {
                    result.connectorsUnfit += connectors.size();
                    hubsUsedThisGroup = 0;
                } else {
                    int hubIndex = 0, countInHub = 0;

                    for (TileWirelessBase connector : connectors) {
                        if (countInHub >= perHub) {
                            hubIndex++;
                            countInHub = 0;
                        }

                        desired.put(connector, hubs.get(hubIndex));
                        countInHub++;
                    }

                    hubsUsedThisGroup = hubIndex + 1;
                }
            } else {
                int hubCount = hubs.size();
                long capacity = (long) hubCount * MAX_LINKS_PER_HUB;

                if (capacity < connectors.size()) {
                    result.connectorsUnfit += connectors.size();
                    hubsUsedThisGroup = 0;
                } else {
                    for (int i = 0; i < connectors.size(); i++) {
                        desired.put(connectors.get(i), hubs.get(i % hubCount));
                    }

                    hubsUsedThisGroup = hubCount;
                }
            }

            if (!matchesDesiredState(connectors, hubs, desired)) {
                for (TileWirelessBase connector : connectors)
                    connector.unlinkAll();
                for (TileWirelessBase hub : hubs)
                    hub.unlinkAll();

                for (Map.Entry<TileWirelessBase, TileWirelessBase> entry : desired.entrySet()) {
                    linkPair(entry.getKey(), entry.getValue());
                }
            }

            result.connectorsLinked += desired.size();
            result.hubsUsed += hubsUsedThisGroup;
        }

        result.hubsQueued = cfg.wirelessAutoPlaceHubs ? getHubShortfallBlocks(state, world).size() : 0;

        return result;
    }

    /** Checks whether every connector/hub in scope already has exactly the connections {@code desired} calls for. */
    @Optional(Names.APPLIED_ENERGISTICS2)
    private static boolean matchesDesiredState(
        List<TileWirelessBase> connectors,
        List<TileWirelessBase> hubs,
        Map<TileWirelessBase, TileWirelessBase> desired
    ) {
        for (TileWirelessBase connector : connectors) {
            TileWirelessBase desiredHub = desired.get(connector);
            Set<Long> actual = connectedCoords(connector);

            if (desiredHub == null) {
                if (!actual.isEmpty()) return false;
            } else if (actual.size() != 1 || !actual.contains(packTile(desiredHub))) { return false; }
        }

        Map<Long, Set<Long>> expectedByHub = new HashMap<>();

        for (Map.Entry<TileWirelessBase, TileWirelessBase> entry : desired.entrySet()) {
            expectedByHub.computeIfAbsent(packTile(entry.getValue()), k -> new HashSet<>()).add(packTile(entry.getKey()));
        }

        for (TileWirelessBase hub : hubs) {
            Set<Long> expected = expectedByHub.getOrDefault(packTile(hub), Collections.emptySet());

            if (!expected.equals(connectedCoords(hub))) return false;
        }

        return true;
    }

    private static long packTile(TileWirelessBase tile) {
        return CoordinatePacker.pack(tile.xCoord, tile.yCoord, tile.zCoord);
    }

    @Optional(Names.APPLIED_ENERGISTICS2)
    private static Set<Long> connectedCoords(TileWirelessBase tile) {
        Set<Long> coords = new HashSet<>();

        for (DimensionalCoord c : tile.getConnectedCoords()) {
            coords.add(CoordinatePacker.pack(c.x, c.y, c.z));
        }

        return coords;
    }

    /** Runs {@link #link} and reports the outcome to the player via chat. */
    @Optional(Names.APPLIED_ENERGISTICS2)
    public static void report(MMState state, World world, EntityPlayer player) {
        LinkResult result = link(state, world, player);

        if (result.error != null) {
            MMUtils.sendErrorToPlayer(player, result.error);
            return;
        }

        if (result.connectorsFound == 0) {
            MMUtils.sendErrorToPlayer(player, "mm.info.error.wireless_link.no_connectors");
            return;
        }

        if (result.connectorsLinked > 0) {
            if (result.hubsQueued > 0) {
                MMUtils.sendInfoToPlayer(
                    player,
                    "mm.info.wireless_link.summary_with_shortfall",
                    result.connectorsLinked,
                    result.hubsUsed,
                    result.hubsQueued
                );
            } else {
                MMUtils.sendInfoToPlayer(player, "mm.info.wireless_link.summary", result.connectorsLinked, result.hubsUsed);
            }
        }

        if (result.connectorsUnfit > 0) {
            if (result.hubsQueued > 0) {
                MMUtils.sendWarningToPlayer(
                    player,
                    "mm.info.error.wireless_link.insufficient_capacity_queued",
                    result.connectorsUnfit,
                    result.hubsQueued
                );
            } else {
                MMUtils.sendWarningToPlayer(player, "mm.info.error.wireless_link.insufficient_capacity", result.connectorsUnfit);
            }
        }
    }

    /**
     * Generates candidate hub placements to cover shortfalls (one per active color group) between the connectors
     * found in the source region and the hubs required to serve them, using the shape/spec engine that
     * {@link MMState}'s GEOMETRY mode uses.
     */
    @Optional(Names.APPLIED_ENERGISTICS2)
    public static List<PendingBlock> getHubShortfallBlocks(MMState state, World world) {
        MMConfig cfg = state.config;

        if (!Location.areCompatible(cfg.wirelessA, cfg.wirelessB) || !cfg.wirelessA.isInWorld(world)) return new ArrayList<>();
        if (!Location.areCompatible(cfg.hubA, cfg.hubB) || !cfg.hubA.isInWorld(world)) return new ArrayList<>();

        List<PendingBlock> all = new ArrayList<>();
        Set<Long> claimed = new HashSet<>();

        for (AEColor color : getActiveColorFilters(cfg)) {
            int numConnectors = scanRegion(world, cfg.wirelessA, cfg.wirelessB, false, color).size();
            if (numConnectors == 0) continue;

            int numHubs = scanRegion(world, cfg.hubA, cfg.hubB, true, color).size();
            int deficit = requiredHubCount(cfg, numConnectors, numHubs) - numHubs;

            if (deficit <= 0) continue;

            all.addAll(queueHubPositions(world, cfg, color, deficit, claimed));
        }

        return all;
    }

    @Optional(Names.APPLIED_ENERGISTICS2)
    private static List<PendingBlock> queueHubPositions(World world, MMConfig cfg, AEColor color, int deficit, Set<Long> claimed) {
        BlockSpec hubSpec = InteropConstants.WIRELESS_HUB_SPEC.toSpec();
        if (hubSpec == null) return new ArrayList<>();

        MMConfig shapeConfig = new MMConfig();
        shapeConfig.shape = cfg.wirelessHubShape.requiresC() ? Shape.CUBE : cfg.wirelessHubShape;
        shapeConfig.coordA = cfg.hubA;
        shapeConfig.coordB = cfg.hubB;
        shapeConfig.coordC = cfg.hubA;
        shapeConfig.corners = new WeightedSpecList(hubSpec);
        shapeConfig.edges = shapeConfig.corners;
        shapeConfig.faces = shapeConfig.corners;
        shapeConfig.volumes = shapeConfig.corners;

        List<PendingBlock> candidates = MMState.getShapeBlocks(world, shapeConfig);

        List<PendingBlock> filtered = new ArrayList<>();

        for (PendingBlock candidate : candidates) {
            if (!world.isAirBlock(candidate.x, candidate.y, candidate.z)) continue;

            long packed = CoordinatePacker.pack(candidate.x, candidate.y, candidate.z);
            if (!claimed.add(packed)) continue;

            if (color != null) {
                pendingColorsFor(world).put(packed, color);
            }

            filtered.add(candidate);

            if (filtered.size() >= deficit) break;
        }

        return filtered;
    }

    /** Colors any previously-queued hub positions that now have a real hub tile placed in the world. */
    @Optional(Names.APPLIED_ENERGISTICS2)
    private static void paintPendingHubs(World world, EntityPlayer player) {
        Map<Long, AEColor> pending = pendingColorsFor(world);
        if (pending.isEmpty()) return;

        Iterator<Map.Entry<Long, AEColor>> it = pending.entrySet().iterator();

        while (it.hasNext()) {
            Map.Entry<Long, AEColor> entry = it.next();

            int x = CoordinatePacker.unpackX(entry.getKey());
            int y = CoordinatePacker.unpackY(entry.getKey());
            int z = CoordinatePacker.unpackZ(entry.getKey());

            if (!world.blockExists(x, y, z)) continue;

            TileEntity te = world.getTileEntity(x, y, z);
            if (!(te instanceof TileWirelessBase wireless) || !wireless.isHub()) continue;

            if (wireless.getColor() != entry.getValue()) {
                wireless.recolourBlock(ForgeDirection.NORTH, entry.getValue(), player);
            }

            it.remove();
        }
    }

    @Optional(Names.APPLIED_ENERGISTICS2)
    private static Map<Long, AEColor> pendingColorsFor(World world) {
        return PENDING_HUB_COLORS.computeIfAbsent(world.provider.dimensionId, k -> new ConcurrentHashMap<>());
    }

    /** Returns the color groups to process. A single {@code null} entry means no filter is set. */
    private static List<AEColor> getActiveColorFilters(MMConfig cfg) {
        if (cfg.wirelessColors == null || cfg.wirelessColors.isEmpty()) { return Collections.singletonList(null); }

        List<AEColor> colors = new ArrayList<>();

        for (int i = cfg.wirelessColors.nextSetBit(0); i >= 0; i = cfg.wirelessColors.nextSetBit(i + 1)) {
            if (i < COLORS.length) colors.add(COLORS[i]);
        }

        return colors.isEmpty() ? Collections.singletonList(null) : colors;
    }

    private static int requiredHubCount(MMConfig cfg, int numConnectors, int numHubs) {
        int aeMinimum = (int) Math.ceil(numConnectors / (double) MAX_LINKS_PER_HUB);

        return switch (cfg.wirelessDistribution) {
            case EVEN_SPREAD -> Math.max(Math.max(1, numHubs), aeMinimum);
            case FIXED_PER_HUB -> Math.max(
                1,
                (int) Math.ceil(numConnectors / (double) MMUtils.clamp(cfg.wirelessCountPerHub, 1, MAX_LINKS_PER_HUB))
            );
        };
    }

    @Optional(Names.APPLIED_ENERGISTICS2)
    private static List<TileWirelessBase> scanRegion(World world, Location a, Location b, boolean wantHubs, AEColor colorFilter) {
        List<TileWirelessBase> found = new ArrayList<>();

        int minX = Math.min(a.x, b.x), minY = Math.min(a.y, b.y), minZ = Math.min(a.z, b.z);
        int maxX = Math.max(a.x, b.x), maxY = Math.max(a.y, b.y), maxZ = Math.max(a.z, b.z);

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    Block block = world.getBlock(x, y, z);
                    if (!InteropConstants.isWirelessConnector(block, OreDictionary.WILDCARD_VALUE)) continue;

                    TileEntity te = world.getTileEntity(x, y, z);
                    if (!(te instanceof TileWirelessBase wireless)) continue;

                    if (wireless.isHub() != wantHubs) continue;
                    if (colorFilter != null && wireless.getColor() != colorFilter) continue;

                    found.add(wireless);
                }
            }
        }

        return found;
    }

    @Optional(Names.APPLIED_ENERGISTICS2)
    private static void linkPair(TileWirelessBase connector, TileWirelessBase hub) {
        DimensionalCoord connectorCoord = new DimensionalCoord(connector.getWorldObj(), connector.xCoord, connector.yCoord, connector.zCoord);
        DimensionalCoord hubCoord = new DimensionalCoord(hub.getWorldObj(), hub.xCoord, hub.yCoord, hub.zCoord);

        connector.addLinkedTarget(hubCoord);
        hub.addLinkedTarget(connectorCoord);

        connector.markDirty();
        hub.markDirty();
    }
}
