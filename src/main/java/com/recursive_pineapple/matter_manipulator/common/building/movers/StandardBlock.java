package com.recursive_pineapple.matter_manipulator.common.building.movers;

import net.minecraft.block.Block;
import net.minecraft.nbt.NBTTagCompound;

import com.github.bsideup.jabel.Desugar;

@Desugar
public record StandardBlock(Block block, int meta, NBTTagCompound tileData) {

}
