package com.recursive_pineapple.matter_manipulator.common.building.movers;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;

import net.minecraftforge.oredict.OreDictionary;

import com.recursive_pineapple.matter_manipulator.common.building.InteropConstants;
import com.recursive_pineapple.matter_manipulator.common.building.PendingMove;

public class WirelessBlockMover extends StandardBlockMover {

    public static final WirelessBlockMover INSTANCE = new WirelessBlockMover();

    @Override
    public boolean canMove(World world, int x, int y, int z) {
        return InteropConstants.isWirelessConnector(world.getBlock(x, y, z), OreDictionary.WILDCARD_VALUE);
    }

    @Override
    public void place(PendingMove pendingMove, World world, int x, int y, int z, StandardBlock standardBlock) {
        if (pendingMove != null) {
            adjustWirelessLinkNbt(standardBlock, pendingMove);
        }

        super.place(pendingMove, world, x, y, z, standardBlock);
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

            if (
                lx >= pendingMove.getSrcMinX() && lx <= pendingMove.getSrcMaxX() &&
                    ly >= pendingMove.getSrcMinY() &&
                    ly <= pendingMove.getSrcMaxY() &&
                    lz >= pendingMove.getSrcMinZ() &&
                    lz <= pendingMove.getSrcMaxZ()
            ) {
                coord.setInteger("x", lx + pendingMove.getMoveOffsetX());
                coord.setInteger("y", ly + pendingMove.getMoveOffsetY());
                coord.setInteger("z", lz + pendingMove.getMoveOffsetZ());
            }

            i++;
        }
    }
}
