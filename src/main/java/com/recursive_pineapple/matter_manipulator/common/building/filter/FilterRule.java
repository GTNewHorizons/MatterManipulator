package com.recursive_pineapple.matter_manipulator.common.building.filter;

import net.minecraft.world.World;

public interface FilterRule {

    boolean matches(World world, int x, int y, int z);
}
