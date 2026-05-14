package com.recursive_pineapple.matter_manipulator.common.building.filter;

import java.util.Arrays;
import java.util.List;

import net.minecraft.world.World;

public final class OrFilterRule implements FilterRule {

    private final List<FilterRule> rules;

    public OrFilterRule(FilterRule... rules) {
        this.rules = Arrays.asList(rules);
    }

    @Override
    public boolean matches(World world, int x, int y, int z) {
        for (FilterRule rule : rules) {
            if (rule.matches(world, x, y, z)) { return true; }
        }

        return false;
    }
}
