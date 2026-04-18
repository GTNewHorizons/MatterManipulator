package com.recursive_pineapple.matter_manipulator.common.building;

import java.util.HashMap;
import java.util.HashSet;

import net.minecraft.block.Block;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import net.minecraftforge.oredict.OreDictionary;

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

import net.bdew.ae2stuff.machines.wireless.TileWireless;
import net.bdew.lib.block.BlockRef;

import org.joml.Vector3i;

public class WirelessLinkFixer {

    private final MMState state;
    private HashMap<Long, Long> wirelessLinkMap = null;
    private boolean initialized = false;

    public WirelessLinkFixer(MMState state) {
        this.state = state;
    }

    public boolean hasLinks() {
        return wirelessLinkMap != null;
    }

    public void tryInit(World world) {
        if (!initialized && Mods.AE2Stuff.isModLoaded()) {
            try {
                init(world);
            } catch (Exception e) {
                MMMod.LOG.error("[WirelessLink] Exception in initWirelessLinkMap", e);
                initialized = true;
            }
        }
    }

    public void tryApply(World world, int x, int y, int z) {
        if (wirelessLinkMap != null && Mods.AE2Stuff.isModLoaded()) {
            apply(world, x, y, z);
        }
    }

    @Optional(Names.AE2STUFF)
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
            HashMap<Long, long[]> tempMap = new HashMap<>();

            for (int sy = srcMinY; sy <= srcMaxY; sy++) {
                for (int sx = srcMinX; sx <= srcMaxX; sx++) {
                    for (int sz = srcMinZ; sz <= srcMaxZ; sz++) {
                        Block block = world.getBlock(sx, sy, sz);
                        if (!InteropConstants.WIRELESS_CONNECTOR.matches(block, OreDictionary.WILDCARD_VALUE)) continue;

                        Vector3i destVec = t.apply(new Vector3i(sx - coordA.x, sy - coordA.y, sz - coordA.z));
                        destVec.add(coordC.x, coordC.y, coordC.z);

                        long srcPacked = CoordinatePacker.pack(sx, sy, sz);
                        long destPacked = CoordinatePacker.pack(destVec.x, destVec.y, destVec.z);

                        allConnectorDests.put(srcPacked, destPacked);

                        TileEntity te = world.getTileEntity(sx, sy, sz);
                        if (!(te instanceof TileWireless tw)) continue;

                        scala.Option<BlockRef> linkOpt = tw.link().value();
                        if (linkOpt.isEmpty()) continue;

                        BlockRef linkRef = linkOpt.get();
                        int lx = linkRef.x();
                        int ly = linkRef.y();
                        int lz = linkRef.z();

                        boolean linkInRegion = lx >= srcMinX && lx <= srcMaxX && ly >= srcMinY && ly <= srcMaxY && lz >= srcMinZ && lz <= srcMaxZ;

                        boolean shouldInclude = false;

                        if (linkInRegion) {
                            shouldInclude = true;
                        } else if (linkExternalHubs) {
                            if (world.blockExists(lx, ly, lz)) {
                                TileEntity linkTe = world.getTileEntity(lx, ly, lz);
                                if (linkTe instanceof TileWireless linkTw) {
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

                        tempMap.put(srcPacked, new long[] {
                            destPacked, linkSrcPacked
                        });
                    }
                }
            }

            if (tempMap.isEmpty()) return;

            wirelessLinkMap = new HashMap<>();

            for (var entry : tempMap.entrySet()) {
                long destPacked = entry.getValue()[0];
                long linkSrcPacked = entry.getValue()[1];

                Long partnerDest = allConnectorDests.get(linkSrcPacked);
                if (partnerDest != null) {
                    wirelessLinkMap.put(destPacked, partnerDest);
                } else if (linkExternalHubs) {
                    wirelessLinkMap.put(destPacked, linkSrcPacked);
                }
            }

            if (state.config.arraySpan != null && !wirelessLinkMap.isEmpty()) {
                Vector3i deltas = MMUtils.getRegionDeltas(coordA, coordB);
                if (deltas == null) return;

                HashSet<Long> internalDests = new HashSet<>(allConnectorDests.values());

                HashMap<Long, Long> baseMap = new HashMap<>(wirelessLinkMap);
                wirelessLinkMap.clear();

                MMUtils.forEachArrayOffset(state.config.arraySpan, deltas, d -> {
                    t.apply(d);

                    for (var baseEntry : baseMap.entrySet()) {
                        long baseDest = baseEntry.getKey();
                        long basePartner = baseEntry.getValue();

                        int bx = CoordinatePacker.unpackX(baseDest) + d.x;
                        int by = CoordinatePacker.unpackY(baseDest) + d.y;
                        int bz = CoordinatePacker.unpackZ(baseDest) + d.z;

                        long offsetDest = CoordinatePacker.pack(bx, by, bz);

                        if (internalDests.contains(basePartner)) {
                            int px = CoordinatePacker.unpackX(basePartner) + d.x;
                            int py = CoordinatePacker.unpackY(basePartner) + d.y;
                            int pz = CoordinatePacker.unpackZ(basePartner) + d.z;

                            wirelessLinkMap.put(offsetDest, CoordinatePacker.pack(px, py, pz));
                        } else {
                            wirelessLinkMap.put(offsetDest, basePartner);
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
                    MMMod.LOG.trace(
                        "[WirelessLink]   ({},{},{}) -> ({},{},{})",
                        CoordinatePacker.unpackX(e.getKey()),
                        CoordinatePacker.unpackY(e.getKey()),
                        CoordinatePacker.unpackZ(e.getKey()),
                        CoordinatePacker.unpackX(e.getValue()),
                        CoordinatePacker.unpackY(e.getValue()),
                        CoordinatePacker.unpackZ(e.getValue())
                    );
                }
            }
        } finally {
            t.uncacheRotation();
        }
    }

    @Optional(Names.AE2STUFF)
    private void apply(World world, int x, int y, int z) {
        long packed = CoordinatePacker.pack(x, y, z);
        Long partnerPacked = wirelessLinkMap.get(packed);
        if (partnerPacked == null) return;

        Block block = world.getBlock(x, y, z);
        if (!InteropConstants.WIRELESS_CONNECTOR.matches(block, OreDictionary.WILDCARD_VALUE)) {
            MMMod.LOG.debug("[WirelessLink] applyWirelessLink({},{},{}): block is not a wireless connector", x, y, z);
            return;
        }

        TileEntity te = world.getTileEntity(x, y, z);
        if (!(te instanceof TileWireless wireless)) {
            MMMod.LOG.debug("[WirelessLink] applyWirelessLink({},{},{}): tile entity is not a TileWireless", x, y, z);
            return;
        }

        int px = CoordinatePacker.unpackX(partnerPacked);
        int py = CoordinatePacker.unpackY(partnerPacked);
        int pz = CoordinatePacker.unpackZ(partnerPacked);

        MMMod.LOG.debug("[WirelessLink] Applying link at ({},{},{}) -> ({},{},{})", x, y, z, px, py, pz);

        wireless.link().set(new BlockRef(px, py, pz));

        scala.Option<BlockRef> verifyLink = wireless.link().value();
        if (verifyLink.isDefined()) {
            BlockRef ref = verifyLink.get();
            MMMod.LOG.trace(
                "[WirelessLink] Verified link at ({},{},{}): ({},{},{})",
                x,
                y,
                z,
                ref.x(),
                ref.y(),
                ref.z()
            );
        } else {
            MMMod.LOG.warn("[WirelessLink] Link was NOT persisted at ({},{},{})", x, y, z);
        }
    }
}
