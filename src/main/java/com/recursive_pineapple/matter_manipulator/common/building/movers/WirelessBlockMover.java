package com.recursive_pineapple.matter_manipulator.common.building.movers;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import net.minecraftforge.oredict.OreDictionary;

import com.recursive_pineapple.matter_manipulator.common.building.InteropConstants;
import com.recursive_pineapple.matter_manipulator.common.building.PendingMove;

public class WirelessBlockMover extends StandardBlockMover {

    public static final WirelessBlockMover INSTANCE = new WirelessBlockMover();

    @Override
    public boolean canMove(World world, int x, int y, int z) {
        return InteropConstants.WIRELESS_CONNECTOR.matches(world.getBlock(x, y, z), OreDictionary.WILDCARD_VALUE);
    }

    @Override
    public void place(PendingMove pendingMove, World world, int x, int y, int z, StandardBlock standardBlock) {
        super.place(pendingMove, world, x, y, z, standardBlock);

        if (pendingMove == null) return;

        TileEntity te = world.getTileEntity(x, y, z);
        if (te == null) return;

        NBTTagCompound nbt = new NBTTagCompound();
        te.writeToNBT(nbt);

        if (!nbt.hasKey("link", 10)) return; // 10 = TAG_Compound

        NBTTagCompound link = nbt.getCompoundTag("link");
        int lx = link.getInteger("x");
        int ly = link.getInteger("y");
        int lz = link.getInteger("z");

        // only adjust if the linked partner was within the source region (i.e. it's also being moved)
        if (
            lx >= pendingMove.getSrcMinX() && lx <= pendingMove.getSrcMaxX() &&
                ly >= pendingMove.getSrcMinY() &&
                ly <= pendingMove.getSrcMaxY() &&
                lz >= pendingMove.getSrcMinZ() &&
                lz <= pendingMove.getSrcMaxZ()
        ) {
            link.setInteger("x", lx + pendingMove.getMoveOffsetX());
            link.setInteger("y", ly + pendingMove.getMoveOffsetY());
            link.setInteger("z", lz + pendingMove.getMoveOffsetZ());
            te.readFromNBT(nbt);
        }
    }
}
