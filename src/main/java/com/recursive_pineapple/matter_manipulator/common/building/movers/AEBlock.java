package com.recursive_pineapple.matter_manipulator.common.building.movers;

import net.minecraft.block.Block;

import appeng.tile.AEBaseTile;

import com.github.bsideup.jabel.Desugar;

@Desugar
public record AEBlock(Block block, int meta, AEBaseTile tile) {}
