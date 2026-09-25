package com.recursive_pineapple.matter_manipulator.common.building.movers;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;

import net.minecraftforge.oredict.OreDictionary;

import appeng.api.util.DimensionalCoord;
import appeng.tile.networking.TileWirelessBase;

import com.recursive_pineapple.matter_manipulator.asm.Optional;
import com.recursive_pineapple.matter_manipulator.common.building.InteropConstants;
import com.recursive_pineapple.matter_manipulator.common.building.PendingMove;
import com.recursive_pineapple.matter_manipulator.common.utils.Mods;
import com.recursive_pineapple.matter_manipulator.common.utils.Mods.Names;

public class WirelessBlockMover extends StandardBlockMover {

    public static final WirelessBlockMover INSTANCE = new WirelessBlockMover();

    @Override
    public boolean canMove(World world, int x, int y, int z) {
        return InteropConstants.isWirelessConnector(world.getBlock(x, y, z), OreDictionary.WILDCARD_VALUE);
    }

    @Override
    public StandardBlock remove(PendingMove pendingMove, World world, int x, int y, int z) {
        if (pendingMove != null && Mods.AppliedEnergistics2.isModLoaded()) { return removeWireless(pendingMove, world, x, y, z); }

        return super.remove(pendingMove, world, x, y, z);
    }

    @Override
    public void place(PendingMove pendingMove, World world, int x, int y, int z, StandardBlock standardBlock) {
        if (pendingMove != null) {
            adjustWirelessLinkNbt(standardBlock, pendingMove);
        }

        super.place(pendingMove, world, x, y, z, standardBlock);

        if (pendingMove != null && Mods.AppliedEnergistics2.isModLoaded()) {
            linkPartnersToNewLocation(world, x, y, z);
        }
    }

    /**
     * AE2 only restores a hub/connector link when both sides list each other. Partners outside the moved region still
     * list the old location, so unlink them here and re-add the link to the new location in
     * {@link #linkPartnersToNewLocation}.
     */
    @Optional(Names.APPLIED_ENERGISTICS2)
    private StandardBlock removeWireless(PendingMove pendingMove, World world, int x, int y, int z) {
        if (!(world.getTileEntity(x, y, z) instanceof TileWirelessBase wireless)) { return super.remove(pendingMove, world, x, y, z); }

        List<DimensionalCoord> links = wireless.getConnectedCoords();

        for (DimensionalCoord link : links) {
            if (isInSourceRegion(pendingMove, link.x, link.y, link.z)) continue;

            TileWirelessBase partner = getWirelessTile(world, link);
            if (partner != null) partner.unlink(wireless);
        }

        StandardBlock standardBlock = super.remove(pendingMove, world, x, y, z);

        // unlinking also removed the external partners from this tile, so put the saved links back
        if (standardBlock.tileData() != null) {
            NBTTagCompound connectedTargets = new NBTTagCompound();
            DimensionalCoord.writeListToNBT(connectedTargets, new ArrayList<>(links));
            standardBlock.tileData().setTag("connectedTargets", connectedTargets);
        }

        return standardBlock;
    }

    /**
     * Makes every loaded partner list the new location, so that the link survives AE2's restore.
     */
    @Optional(Names.APPLIED_ENERGISTICS2)
    private void linkPartnersToNewLocation(World world, int x, int y, int z) {
        if (!(world.getTileEntity(x, y, z) instanceof TileWirelessBase wireless)) return;

        DimensionalCoord location = new DimensionalCoord(world, x, y, z);

        for (DimensionalCoord link : wireless.getConnectedCoords()) {
            TileWirelessBase partner = getWirelessTile(world, link);
            if (partner != null) partner.addLinkedTarget(location);
        }
    }

    @Optional(Names.APPLIED_ENERGISTICS2)
    private static TileWirelessBase getWirelessTile(World world, DimensionalCoord coord) {
        if (coord.getDimension() != world.provider.dimensionId) return null;
        if (!world.blockExists(coord.x, coord.y, coord.z)) return null;

        return world.getTileEntity(coord.x, coord.y, coord.z) instanceof TileWirelessBase tile ? tile : null;
    }

    private static boolean isInSourceRegion(PendingMove pendingMove, int x, int y, int z) {
        return x >= pendingMove.getSrcMinX() && x <= pendingMove.getSrcMaxX() &&
            y >= pendingMove.getSrcMinY() &&
            y <= pendingMove.getSrcMaxY() &&
            z >= pendingMove.getSrcMinZ() &&
            z <= pendingMove.getSrcMaxZ();
    }

    private void adjustWirelessLinkNbt(StandardBlock standardBlock, PendingMove pendingMove) {
        NBTTagCompound tileData = standardBlock.tileData();
        if (tileData == null || !tileData.hasKey("connectedTargets")) return;

        NBTTagCompound connectedTargets = tileData.getCompoundTag("connectedTargets");

        int i = 0;
        while (connectedTargets.hasKey("pos#" + i)) {
            NBTTagCompound coord = connectedTargets.getCompoundTag("pos#" + i);
            int lx = coord.getInteger("x");
            int ly = coord.getInteger("y");
            int lz = coord.getInteger("z");

            if (isInSourceRegion(pendingMove, lx, ly, lz)) {
                coord.setInteger("x", lx + pendingMove.getMoveOffsetX());
                coord.setInteger("y", ly + pendingMove.getMoveOffsetY());
                coord.setInteger("z", lz + pendingMove.getMoveOffsetZ());
            }

            i++;
        }
    }
}
