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
    public StandardBlock snapshot(PendingMove.PendingMoveApplyContext ctx, World world, int x, int y, int z) {
        if (ctx != null && Mods.AppliedEnergistics2.isModLoaded()) { return removeWireless(ctx, world, x, y, z); }

        return super.snapshot(ctx, world, x, y, z);
    }

    @Override
    public void place(PendingMove.PendingMoveApplyContext ctx, World world, int x, int y, int z, StandardBlock standardBlock) {
        if (ctx != null) {
            adjustWirelessLinkNbt(standardBlock, ctx);
        }

        super.place(ctx, world, x, y, z, standardBlock);

        if (ctx != null && Mods.AppliedEnergistics2.isModLoaded()) {
            linkPartnersToNewLocation(world, x, y, z);
        }
    }

    /**
     * AE2 only restores a hub/connector link when both sides list each other. Partners outside the moved region still
     * list the old location, so unlink them here and re-add the link to the new location in
     * {@link #linkPartnersToNewLocation}.
     */
    @Optional(Names.APPLIED_ENERGISTICS2)
    private StandardBlock removeWireless(PendingMove.PendingMoveApplyContext ctx, World world, int x, int y, int z) {
        if (!(world.getTileEntity(x, y, z) instanceof TileWirelessBase wireless)) { return super.snapshot(ctx, world, x, y, z); }

        List<DimensionalCoord> links = wireless.getConnectedCoords();

        for (DimensionalCoord link : links) {
            if (ctx.pendingMove.isInSourceRegion(link.x, link.y, link.z)) continue;

            TileWirelessBase partner = getWirelessTile(world, link);
            if (partner != null) partner.unlink(wireless);
        }

        StandardBlock standardBlock = super.snapshot(ctx, world, x, y, z);

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

    private void adjustWirelessLinkNbt(StandardBlock standardBlock, PendingMove.PendingMoveApplyContext ctx) {
        NBTTagCompound tileData = standardBlock.tileData();
        if (tileData == null || !tileData.hasKey("connectedTargets")) return;

        NBTTagCompound connectedTargets = tileData.getCompoundTag("connectedTargets");

        int i = 0;
        while (connectedTargets.hasKey("pos#" + i)) {
            NBTTagCompound coord = connectedTargets.getCompoundTag("pos#" + i);
            int lx = coord.getInteger("x");
            int ly = coord.getInteger("y");
            int lz = coord.getInteger("z");

            if (ctx.pendingMove.isInSourceRegion(lx, ly, lz)) {
                coord.setInteger("x", lx + ctx.pendingMove.getMoveOffsetX());
                coord.setInteger("y", ly + ctx.pendingMove.getMoveOffsetY());
                coord.setInteger("z", lz + ctx.pendingMove.getMoveOffsetZ());
            }

            i++;
        }
    }
}
