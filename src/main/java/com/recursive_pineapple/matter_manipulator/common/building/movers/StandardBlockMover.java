package com.recursive_pineapple.matter_manipulator.common.building.movers;

import net.minecraft.block.Block;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import com.recursive_pineapple.matter_manipulator.common.building.PendingMove;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.Transform;

public class StandardBlockMover implements BlockMover<StandardBlock> {

    public static final StandardBlockMover INSTANCE = new StandardBlockMover();

    @Override
    public boolean canMove(World world, int x, int y, int z) {
        return true;
    }

    @Override
    public StandardBlock snapshot(PendingMove.PendingMoveApplyContext ctx, World world, int x, int y, int z) {
        Block block = world.getBlock(x, y, z);
        int meta = world.getBlockMetadata(x, y, z);
        NBTTagCompound tag = null;

        TileEntity te = world.getTileEntity(x, y, z);

        if (te != null) {
            tag = new NBTTagCompound();
            te.writeToNBT(tag);
        }

        return new StandardBlock(block, meta, tag);
    }

    @Override
    public void place(PendingMove.PendingMoveApplyContext ctx, World world, int x, int y, int z, StandardBlock standardBlock) {
        world.setBlock(x, y, z, standardBlock.block(), standardBlock.meta(), 3);

        if (standardBlock.tileData() != null) {
            TileEntity te = TileEntity.createAndLoadEntity(standardBlock.tileData());

            te.xCoord = x;
            te.yCoord = y;
            te.zCoord = z;

            world.setTileEntity(x, y, z, te);
        }
    }

    @Override
    public void transform(PendingMove.PendingMoveApplyContext ctx, Transform transform, StandardBlock standardBlock) {

    }

    @Override
    public boolean snapshotEarly(World world, int x, int y, int z) {
        Block block = world.getBlock(x, y, z);

        // Is there a better way to check if a block can stand alone?
        return block != null && !block.canPlaceBlockAt(world, x, -256, z);
    }
}
