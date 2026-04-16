package com.recursive_pineapple.matter_manipulator.common.building.movers;

import net.minecraft.world.World;

import com.recursive_pineapple.matter_manipulator.asm.Optional;
import com.recursive_pineapple.matter_manipulator.common.utils.Mods.Names;

public enum BlockMovers {

    @Optional(Names.FORGE_MULTIPART)
    FMP(FMPBlockMover.INSTANCE),
    @Optional(Names.GREG_TECH_NH)
    GT(GTBlockMover.INSTANCE),
    @Optional(Names.AE2STUFF)
    Wireless(WirelessBlockMover.INSTANCE),
    Standard(StandardBlockMover.INSTANCE);

    public final BlockMover<?> blockMover;

    BlockMovers(BlockMover<?> blockMover) {
        this.blockMover = blockMover;
    }

    public static BlockMover<?> getBlockMover(World world, int x, int y, int z) {
        for (BlockMovers blockMover : BlockMovers.values()) {
            if (blockMover.blockMover.canMove(world, x, y, z)) return blockMover.blockMover;
        }

        throw new IllegalStateException();
    }
}
