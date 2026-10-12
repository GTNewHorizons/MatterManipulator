package com.recursive_pineapple.matter_manipulator.common.building.movers;

import net.minecraft.block.Block;
import net.minecraft.world.World;

/**
 * Mover for a block that needs a support block to stay on to
 * Same as StandardBlockMover but it gets snapshot early
 */
public class DependentBlockMover extends StandardBlockMover {

    public static DependentBlockMover INSTANCE = new DependentBlockMover();

    @Override
    public boolean canMove(World world, int x, int y, int z) {
        Block block = world.getBlock(x, y, z);

        // At least it guarantees neighbour of air blocks :aga:
        return block != null && !block.canPlaceBlockAt(world, x, -256, z);
    }

    @Override
    public boolean snapshotEarly() {
        return true;
    }
}
