package com.recursive_pineapple.matter_manipulator.common.building.movers;

import net.minecraft.block.Block;

import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;

import com.github.bsideup.jabel.Desugar;

@Desugar
public record GTBlock(Block block, int meta, IGregTechTileEntity igte, IMetaTileEntity mte) {

}
