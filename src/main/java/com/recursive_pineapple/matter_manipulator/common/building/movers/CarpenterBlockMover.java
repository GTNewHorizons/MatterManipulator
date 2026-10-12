package com.recursive_pineapple.matter_manipulator.common.building.movers;

import net.minecraft.block.Block;
import net.minecraft.world.World;

import com.carpentersblocks.tileentity.TEBase;
import com.recursive_pineapple.matter_manipulator.common.building.CarpentersBlocksAnalysisResult;
import com.recursive_pineapple.matter_manipulator.common.building.PendingMove;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.Transform;

public class CarpenterBlockMover implements BlockMover<CarpenterBlock> {

    public static CarpenterBlockMover INSTANCE = new CarpenterBlockMover();

    @Override
    public boolean canMove(World world, int x, int y, int z) {
        return world.getTileEntity(x, y, z) instanceof TEBase;
    }

    @Override
    public CarpenterBlock snapshot(PendingMove.PendingMoveApplyContext ctx, World world, int x, int y, int z) {
        Block block = world.getBlock(x, y, z);
        int meta = world.getBlockMetadata(x, y, z);
        TEBase tile = (TEBase) world.getTileEntity(x, y, z);

        return new CarpenterBlock(block, meta, tile);
    }

    @Override
    public void place(PendingMove.PendingMoveApplyContext ctx, World world, int x, int y, int z, CarpenterBlock carpenterBlock) {
        world.setBlock(x, y, z, carpenterBlock.block(), carpenterBlock.meta(), 3);

        TEBase tile = carpenterBlock.tile();

        tile.xCoord = x;
        tile.yCoord = y;
        tile.zCoord = z;
        tile.validate();

        world.setTileEntity(x, y, z, tile);

        tile.updateEntity();
    }

    @Override
    public void transform(PendingMove.PendingMoveApplyContext ctx, Transform transform, CarpenterBlock carpenterBlock) {
        TEBase tile = carpenterBlock.tile();

        // Just use the function from analysis :aga:

        CarpentersBlocksAnalysisResult analysis = CarpentersBlocksAnalysisResult.analyze(tile);

        analysis.transform(transform);
        tile.setData(analysis.data);
    }
}
