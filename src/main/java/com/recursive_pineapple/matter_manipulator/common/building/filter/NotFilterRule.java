package com.recursive_pineapple.matter_manipulator.common.building.filter;

import net.minecraft.world.World;

public final class NotFilterRule implements FilterRule {

    private final FilterRule rule;

    public NotFilterRule(FilterRule rule) {
        this.rule = rule;
    }

    @Override
    public boolean matches(World world, int x, int y, int z) {
        return !rule.matches(world, x, y, z);
    }
}
