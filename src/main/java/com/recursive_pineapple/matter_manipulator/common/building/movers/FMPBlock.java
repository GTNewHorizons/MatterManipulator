package com.recursive_pineapple.matter_manipulator.common.building.movers;

import net.minecraft.block.Block;

import com.github.bsideup.jabel.Desugar;

import codechicken.multipart.TileMultipart;

@Desugar
public record FMPBlock(Block block, int meta, TileMultipart tile) {

}
