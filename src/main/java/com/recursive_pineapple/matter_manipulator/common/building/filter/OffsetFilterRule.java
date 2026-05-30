package com.recursive_pineapple.matter_manipulator.common.building.filter;

import net.minecraft.block.Block;
import net.minecraft.world.World;

public abstract class OffsetFilterRule implements FilterRule {

    protected final OffsetSet offsetSet;

    protected OffsetFilterRule(OffsetSet offsetSet) {
        this.offsetSet = offsetSet;
    }

    protected Block getBlock(World world, int x, int y, int z, Offset offset) {
        return world.getBlock(x + offset.dx, y + offset.dy, z + offset.dz);
    }

    protected int getBlockMetadata(World world, int x, int y, int z, Offset offset) {
        return world.getBlockMetadata(x + offset.dx, y + offset.dy, z + offset.dz);
    }
}
