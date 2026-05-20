package com.recursive_pineapple.matter_manipulator.common.building;

import java.util.HashMap;
import java.util.HashSet;

import net.minecraft.block.Block;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import net.minecraftforge.oredict.OreDictionary;

import appeng.api.util.DimensionalCoord;
import appeng.tile.networking.TileWirelessBase;

import com.gtnewhorizon.gtnhlib.util.CoordinatePacker;
import com.recursive_pineapple.matter_manipulator.MMMod;
import com.recursive_pineapple.matter_manipulator.asm.Optional;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.Location;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.MMState;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.MMState.PlaceMode;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.Transform;
import com.recursive_pineapple.matter_manipulator.common.utils.MMUtils;
import com.recursive_pineapple.matter_manipulator.common.utils.Mods;
import com.recursive_pineapple.matter_manipulator.common.utils.Mods.Names;

import org.joml.Vector3i;

public class WirelessLinkFixer {

    private final MMState state;
    private HashMap<Long, HashSet<Long>> wirelessLinkMap = null;
    private boolean initialized = false;

    public WirelessLinkFixer(MMState state) {
        this.state = state;
    }

    public boolean hasLinks() {
        return wirelessLinkMap != null;
    }

    public void tryInit(World world) {
        if (!initialized && Mods.AppliedEnergistics2.isModLoaded()) {
            try {
                init(world);
            } catch (Exception e) {
                MMMod.LOG.error("[WirelessLink] Exception in initWirelessLinkMap", e);
                initialized = true;
            }
        }
    }

    public void tryApply(World world, int x, int y, int z) {
        if (wirelessLinkMap != null && Mods.AppliedEnergistics2.isModLoaded()) {
            apply(world, x, y, z);
        }
    }

    @Optional(Names.APPLIED_ENERGISTICS2)
    private void init(World world) {
        initialized = true;

        if (state.config.placeMode != PlaceMode.COPYING) {
            MMMod.LOG.debug("[WirelessLink] Skipping: not in COPYING mode (mode={})", state.config.placeMode);
            return;
        }

        Location coordA = state.config.coordA;
        Location coordB = state.config.coordB;
        Location coordC = state.config.coordC;

        if (!Location.areCompatible(coordA, coordB, coordC)) {
            MMMod.LOG.debug("[WirelessLink] Skipping: coordinates not compatible");
            return;
        }

        boolean linkExternalHubs = state.config.linkExternalHubs;

        MMMod.LOG.debug("[WirelessLink] Initializing wireless link map with linkExternalHubs={}", linkExternalHubs);

        int srcMinX = Math.min(coordA.x, coordB.x);
        int srcMinY = Math.min(coordA.y, coordB.y);
        int srcMinZ = Math.min(coordA.z, coordB.z);
        int srcMaxX = Math.max(coordA.x, coordB.x);
        int srcMaxY = Math.max(coordA.y, coordB.y);
        int srcMaxZ = Math.max(coordA.z, coordB.z);

        Transform t = state.getTransform();
        t.cacheRotation();

        try {
            HashMap<Long, Long> allConnectorDests = new HashMap<>();
            HashMap<Long, HashSet<Long>> tempMap = new HashMap<>();

            for (int sy = srcMinY; sy <= srcMaxY; sy++) {
                for (int sx = srcMinX; sx <= srcMaxX; sx++) {
                    for (int sz = srcMinZ; sz <= srcMaxZ; sz++) {
                        Block block = world.getBlock(sx, sy, sz);
                        if (!InteropConstants.isWirelessConnector(block, OreDictionary.WILDCARD_VALUE)) continue;

                        Vector3i destVec = t.apply(new Vector3i(sx - coordA.x, sy - coordA.y, sz - coordA.z));
                        destVec.add(coordC.x, coordC.y, coordC.z);

                        long srcPacked = CoordinatePacker.pack(sx, sy, sz);
                        long destPacked = CoordinatePacker.pack(destVec.x, destVec.y, destVec.z);

                        allConnectorDests.put(srcPacked, destPacked);

                        TileEntity te = world.getTileEntity(sx, sy, sz);
                        if (!(te instanceof TileWirelessBase tw)) continue;

                        for (DimensionalCoord linkCoord : tw.getConnectedCoords()) {
                            if (linkCoord.getDimension() != world.provider.dimensionId) continue;

                            int lx = linkCoord.x;
                            int ly = linkCoord.y;
                            int lz = linkCoord.z;

                            boolean linkInRegion = lx >= srcMinX && lx <= srcMaxX && ly >= srcMinY && ly <= srcMaxY && lz >= srcMinZ && lz <= srcMaxZ;

                            boolean shouldInclude = false;

                            if (linkInRegion) {
                                shouldInclude = true;
                            } else if (linkExternalHubs) {
                                if (world.blockExists(lx, ly, lz)) {
                                    TileEntity linkTe = world.getTileEntity(lx, ly, lz);
                                    if (linkTe instanceof TileWirelessBase linkTw) {
                                        if (linkTw.isHub()) {
                                            shouldInclude = true;
                                        }
                                    }
                                }
                            }

                            if (!shouldInclude) continue;

                            MMMod.LOG.trace(
                                "[WirelessLink] Found connector at ({},{},{}) linked to ({},{},{}), linkInRegion={}",
                                sx,
                                sy,
                                sz,
                                lx,
                                ly,
                                lz,
                                linkInRegion
                            );

                            long linkSrcPacked = CoordinatePacker.pack(lx, ly, lz);

                            tempMap.computeIfAbsent(srcPacked, ignored -> new HashSet<>()).add(linkSrcPacked);
                        }
                    }
                }
            }

            if (tempMap.isEmpty()) return;

            wirelessLinkMap = new HashMap<>();

            for (var entry : tempMap.entrySet()) {
                long destPacked = allConnectorDests.get(entry.getKey());

                for (long linkSrcPacked : entry.getValue()) {
                    Long partnerDest = allConnectorDests.get(linkSrcPacked);
                    if (partnerDest != null) {
                        addLink(wirelessLinkMap, destPacked, partnerDest);
                    } else if (linkExternalHubs) {
                        addLink(wirelessLinkMap, destPacked, linkSrcPacked);
                    }
                }
            }

            if (state.config.arraySpan != null && !wirelessLinkMap.isEmpty()) {
                Vector3i deltas = MMUtils.getRegionDeltas(coordA, coordB);
                if (deltas == null) return;

                HashSet<Long> internalDests = new HashSet<>(allConnectorDests.values());

                HashMap<Long, HashSet<Long>> baseMap = new HashMap<>();
                wirelessLinkMap.forEach((key, value) -> baseMap.put(key, new HashSet<>(value)));
                wirelessLinkMap.clear();

                MMUtils.forEachArrayOffset(state.config.arraySpan, deltas, d -> {
                    t.apply(d);

                    for (var baseEntry : baseMap.entrySet()) {
                        long baseDest = baseEntry.getKey();

                        int bx = CoordinatePacker.unpackX(baseDest) + d.x;
                        int by = CoordinatePacker.unpackY(baseDest) + d.y;
                        int bz = CoordinatePacker.unpackZ(baseDest) + d.z;

                        long offsetDest = CoordinatePacker.pack(bx, by, bz);

                        for (long basePartner : baseEntry.getValue()) {
                            if (internalDests.contains(basePartner)) {
                                int px = CoordinatePacker.unpackX(basePartner) + d.x;
                                int py = CoordinatePacker.unpackY(basePartner) + d.y;
                                int pz = CoordinatePacker.unpackZ(basePartner) + d.z;

                                addLink(wirelessLinkMap, offsetDest, CoordinatePacker.pack(px, py, pz));
                            } else {
                                addLink(wirelessLinkMap, offsetDest, basePartner);
                            }
                        }
                    }
                });
            }

            if (wirelessLinkMap.isEmpty()) {
                MMMod.LOG.debug("[WirelessLink] Link map is empty after processing, setting to null");
                wirelessLinkMap = null;
            } else {
                MMMod.LOG.debug("[WirelessLink] Built link map with {} entries", wirelessLinkMap.size());
                for (var e : wirelessLinkMap.entrySet()) {
                    for (long partner : e.getValue()) {
                        MMMod.LOG.trace(
                            "[WirelessLink]   ({},{},{}) -> ({},{},{})",
                            CoordinatePacker.unpackX(e.getKey()),
                            CoordinatePacker.unpackY(e.getKey()),
                            CoordinatePacker.unpackZ(e.getKey()),
                            CoordinatePacker.unpackX(partner),
                            CoordinatePacker.unpackY(partner),
                            CoordinatePacker.unpackZ(partner)
                        );
                    }
                }
            }
        } finally {
            t.uncacheRotation();
        }
    }

    private static void addLink(HashMap<Long, HashSet<Long>> links, long connector, long partner) {
        links.computeIfAbsent(connector, ignored -> new HashSet<>()).add(partner);
    }

    @Optional(Names.APPLIED_ENERGISTICS2)
    private void apply(World world, int x, int y, int z) {
        long packed = CoordinatePacker.pack(x, y, z);
        HashSet<Long> partnerPackedSet = wirelessLinkMap.get(packed);
        if (partnerPackedSet == null) return;

        Block block = world.getBlock(x, y, z);
        if (!InteropConstants.isWirelessConnector(block, OreDictionary.WILDCARD_VALUE)) {
            MMMod.LOG.debug("[WirelessLink] applyWirelessLink({},{},{}): block is not a wireless connector", x, y, z);
            return;
        }

        TileEntity te = world.getTileEntity(x, y, z);
        if (!(te instanceof TileWirelessBase wireless)) {
            MMMod.LOG.debug("[WirelessLink] applyWirelessLink({},{},{}): tile entity is not a TileWirelessBase", x, y, z);
            return;
        }

        wireless.doUnlink();

        for (long partnerPacked : partnerPackedSet) {
            int px = CoordinatePacker.unpackX(partnerPacked);
            int py = CoordinatePacker.unpackY(partnerPacked);
            int pz = CoordinatePacker.unpackZ(partnerPacked);

            MMMod.LOG.debug("[WirelessLink] Applying link at ({},{},{}) -> ({},{},{})", x, y, z, px, py, pz);

            wireless.injectConnection(new DimensionalCoord(world, px, py, pz));
        }

        wireless.markDirty();
    }
}
