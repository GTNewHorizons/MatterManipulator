package com.recursive_pineapple.matter_manipulator.common.building.movers;

import net.minecraft.world.World;

import com.recursive_pineapple.matter_manipulator.common.building.PendingMove;

public interface BlockMover<State> {

    boolean canMove(World world, int x, int y, int z);

    State remove(PendingMove pendingMove, World world, int x, int y, int z);

    void place(PendingMove pendingMove, World world, int x, int y, int z, State state);
}
