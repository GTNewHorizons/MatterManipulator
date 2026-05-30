package com.recursive_pineapple.matter_manipulator.common.building.movers;

import net.minecraft.block.Block;
import net.minecraft.world.World;

import com.recursive_pineapple.matter_manipulator.common.building.PendingMove;

import codechicken.multipart.MultipartHelper;
import codechicken.multipart.TileMultipart;

public class FMPBlockMover implements BlockMover<FMPBlock> {

    public static final FMPBlockMover INSTANCE = new FMPBlockMover();

    @Override
    public boolean canMove(World world, int x, int y, int z) {
        return world.getTileEntity(x, y, z) instanceof TileMultipart;
    }

    @Override
    public FMPBlock remove(PendingMove pendingMove, World world, int x, int y, int z) {
        Block block = world.getBlock(x, y, z);
        int meta = world.getBlockMetadata(x, y, z);
        TileMultipart te = (TileMultipart) world.getTileEntity(x, y, z);

        world.setTileEntity(x, y, z, null);
        world.setBlockToAir(x, y, z);

        return new FMPBlock(block, meta, te);
    }

    @Override
    public void place(PendingMove pendingMove, World world, int x, int y, int z, FMPBlock fmpBlock) {
        world.setBlock(x, y, z, fmpBlock.block(), fmpBlock.meta(), 3);

        fmpBlock.tile().xCoord = x;
        fmpBlock.tile().yCoord = y;
        fmpBlock.tile().zCoord = z;

        fmpBlock.tile().validate();
        world.setTileEntity(x, y, z, fmpBlock.tile());

        fmpBlock.tile().onMoved();

        world.markBlockForUpdate(x, y, z);
        world.func_147451_t(x, y, z);
        fmpBlock.tile().markDirty();
        fmpBlock.tile().markRender();
        MultipartHelper.sendDescPacket(world, fmpBlock.tile());
    }
}
