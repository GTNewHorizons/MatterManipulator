package com.recursive_pineapple.matter_manipulator.common.building.movers;

import net.minecraft.block.Block;

import com.carpentersblocks.tileentity.TEBase;
import com.github.bsideup.jabel.Desugar;

@Desugar
public record CarpenterBlock(Block block, int meta, TEBase tile) {}
