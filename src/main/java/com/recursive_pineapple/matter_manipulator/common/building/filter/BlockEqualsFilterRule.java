package com.recursive_pineapple.matter_manipulator.common.building.filter;

import net.minecraft.block.Block;
import net.minecraft.world.World;

public final class BlockEqualsFilterRule extends OffsetFilterRule {

    private final Block expected;
    private final int meta;
    private final boolean negated;

    public BlockEqualsFilterRule(OffsetSet offsetSet, Block expected, int meta, boolean negated) {
        super(offsetSet);

        if (expected == null) { throw new IllegalArgumentException("expected block cannot be null"); }

        this.expected = expected;
        this.meta = meta;
        this.negated = negated;
    }

    @Override
    public boolean matches(World world, int x, int y, int z) {
        switch (this.offsetSet.mode()) {
            case SINGLE -> {
                return validateOffset(world, x, y, z, this.offsetSet.offsets().get(0));
            }
            case ANY -> {
                for (Offset offset : this.offsetSet.offsets()) {
                    if (validateOffset(world, x, y, z, offset)) { return true; }
                }
                return false;
            }
            case ALL -> {
                for (Offset offset : this.offsetSet.offsets()) {
                    if (!validateOffset(world, x, y, z, offset)) { return false; }
                }
                return true;
            }
            default -> throw new IllegalStateException("Unexpected value: " + this.offsetSet.mode());
        }
    }

    private boolean validateOffset(World world, int x, int y, int z, Offset offset) {
        boolean match = getBlock(world, x, y, z, offset) == expected && (this.meta < 0 || getBlockMetadata(world, x, y, z, offset) == meta);
        return negated != match;
    }
}
