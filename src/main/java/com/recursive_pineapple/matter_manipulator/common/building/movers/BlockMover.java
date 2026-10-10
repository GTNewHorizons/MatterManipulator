package com.recursive_pineapple.matter_manipulator.common.building.movers;

import net.minecraft.world.World;

import com.recursive_pineapple.matter_manipulator.common.building.PendingMove;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.Transform;

public interface BlockMover<State> {

    boolean canMove(World world, int x, int y, int z);

    State snapshot(PendingMove.PendingMoveApplyContext ctx, World world, int x, int y, int z);

    void place(PendingMove.PendingMoveApplyContext ctx, World world, int x, int y, int z, State state);

    void transform(PendingMove.PendingMoveApplyContext ctx, Transform transform, State state);

    default boolean snapshotEarly() {
        return false;
    }
}
