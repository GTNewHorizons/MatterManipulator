package com.recursive_pineapple.matter_manipulator.common.building.movers;

import net.minecraft.block.Block;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import com.recursive_pineapple.matter_manipulator.common.building.PendingMove;

public class StandardBlockMover implements BlockMover<StandardBlock> {

    public static final StandardBlockMover INSTANCE = new StandardBlockMover();

    @Override
    public boolean canMove(World world, int x, int y, int z) {
        return true;
    }

    @Override
    public StandardBlock remove(PendingMove pendingMove, World world, int x, int y, int z) {
        Block block = world.getBlock(x, y, z);
        int meta = world.getBlockMetadata(x, y, z);
        NBTTagCompound tag = null;

        TileEntity te = world.getTileEntity(x, y, z);

        if (te != null) {
            tag = new NBTTagCompound();
            te.writeToNBT(tag);
        }

        world.setTileEntity(x, y, z, null);
        world.setBlockToAir(x, y, z);

        return new StandardBlock(block, meta, tag);
    }

    @Override
    public void place(PendingMove pendingMove, World world, int x, int y, int z, StandardBlock standardBlock) {
        world.setBlock(x, y, z, standardBlock.block(), standardBlock.meta(), 3);

        if (standardBlock.tileData() != null) {
            TileEntity te = TileEntity.createAndLoadEntity(standardBlock.tileData());

            te.xCoord = x;
            te.yCoord = y;
            te.zCoord = z;

            world.setTileEntity(x, y, z, te);
        }
    }
}
