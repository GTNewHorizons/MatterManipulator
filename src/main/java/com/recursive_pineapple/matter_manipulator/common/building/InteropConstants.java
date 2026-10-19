package com.recursive_pineapple.matter_manipulator.common.building;

import net.minecraft.block.Block;
import net.minecraft.block.material.MaterialLiquid;
import net.minecraft.block.material.MaterialTransparent;
import net.minecraft.init.Blocks;
import net.minecraft.tileentity.TileEntity;

import net.minecraftforge.oredict.OreDictionary;

import gregtech.api.GregTechAPI;

import appeng.block.qnb.BlockQuantumBase;
import appeng.tile.qnb.TileQuantumBridge;

import com.gtnewhorizon.gtnhlib.util.data.LazyBlock;
import com.recursive_pineapple.matter_manipulator.asm.Optional;
import com.recursive_pineapple.matter_manipulator.common.utils.LazyBlockSpec;
import com.recursive_pineapple.matter_manipulator.common.utils.MMValues;
import com.recursive_pineapple.matter_manipulator.common.utils.Mods;
import com.recursive_pineapple.matter_manipulator.common.utils.Mods.Names;

/**
 * Various constants or static methods used for interop.
 */
public class InteropConstants {

    private InteropConstants() {}

    public static final LazyBlock BRIGHT_AIR = new LazyBlock(Mods.GalacticraftCore, "tile.brightAir", OreDictionary.WILDCARD_VALUE);
    public static final LazyBlock ARCANE_LAMP_LIGHT = new LazyBlock(Mods.Thaumcraft, "blockAiry", 2);
    public static final LazyBlock TC_STONE_DEVICE = new LazyBlock(Mods.Thaumcraft, "blockStoneDevice");
    public static final LazyBlock WIRELESS_CONNECTOR = new LazyBlock(Mods.AppliedEnergistics2, "tile.BlockWirelessConnector");
    public static final LazyBlock WIRELESS_HUB = new LazyBlock(Mods.AppliedEnergistics2, "tile.BlockWirelessHub");
    public static final LazyBlockSpec AE_BLOCK_CABLE = LazyBlockSpec.ofBlock(Mods.AppliedEnergistics2, "tile.BlockCableBus", 0);
    public static final LazyBlock FMP_BLOCK = new LazyBlock(Mods.ForgeMultipart, "block");
    public static final LazyBlock OC_CABLE = new LazyBlock(Mods.OpenComputers, "cable");
    public static final LazyBlockSpec OC_TRANSPOSER = LazyBlockSpec.ofBlock(Mods.OpenComputers, "transposer", MMValues.W);
    public static final LazyBlockSpec OC_MICROCONTROLLER = LazyBlockSpec.ofBlock(Mods.OpenComputers, "microcontroller", MMValues.W);
    public static final LazyBlockSpec ENDER_STORAGE = LazyBlockSpec.ofBlock(Mods.EnderStorage, "enderChest", MMValues.W);

    public static boolean isQuantumBridge(TileEntity tile) {
        return tile != null && Mods.AppliedEnergistics2.isModLoaded() && isAEQuantumBridge(tile);
    }

    public static boolean isQuantumBridge(Block block) {
        return block != null && Mods.AppliedEnergistics2.isModLoaded() && isAEQuantumBridge(block);
    }

    @Optional(Names.APPLIED_ENERGISTICS2)
    private static boolean isAEQuantumBridge(TileEntity tile) {
        return tile instanceof TileQuantumBridge;
    }

    @Optional(Names.APPLIED_ENERGISTICS2)
    private static boolean isAEQuantumBridge(Block block) {
        return block instanceof BlockQuantumBase;
    }

    public static boolean isWirelessConnector(Block block, int meta) {
        return WIRELESS_CONNECTOR.matches(block, meta) || WIRELESS_HUB.matches(block, meta);
    }

    public static boolean isAir(Block block, int meta) {
        if (block.getMaterial() instanceof MaterialTransparent) return true;

        return false;
    }

    public static boolean skipWhenCopying(Block block, int meta) {
        if (block.getMaterial() instanceof MaterialLiquid) return true;

        if (Mods.GregTech.isModLoaded() && isGTRenderer(block)) return true;
        if (BRIGHT_AIR.matches(block, meta)) return true;
        if (ARCANE_LAMP_LIGHT.matches(block, meta)) return true;

        return false;
    }

    public static boolean shouldDropItem(Block block, int meta) {
        if (Mods.GregTech.isModLoaded() && isGTRenderer(block)) return false;

        // Don't check for MaterialTransparent because it could include things like nitor
        if (BRIGHT_AIR.matches(block, meta)) return false;
        if (ARCANE_LAMP_LIGHT.matches(block, meta)) return false;

        return true;
    }

    @Optional(Names.GREG_TECH_NH)
    private static boolean isGTRenderer(Block block) {
        return GregTechAPI.isGTRenderer(block);
    }

    /**
     * A formed infusion altar replaces its arcane stone with pillar blocks (meta 3 and 4) that have no item form. They
     * drop the arcane stone, and the matrix forms them again when it is activated with a wand.
     */
    public static boolean isInfusionPillar(Block block, int meta) {
        return (meta == 3 || meta == 4) && TC_STONE_DEVICE.matches(block, meta);
    }

    private static final String MRTJP_INSTANCED_BLOCK = "mrtjp.core.block.InstancedBlock";

    /**
     * MrTJPCore blocks (ProjectRed lamps, machines, ...) keep part of their item metadata in the tile entity, for
     * example the inverted flag of a lamp. Only the tile's pick block has the full item metadata.
     */
    public static boolean keepsItemMetaInTile(Block block) {
        for (Class<?> c = block.getClass(); c != null && c != Block.class; c = c.getSuperclass()) {
            if (c.getName().equals(MRTJP_INSTANCED_BLOCK)) return true;
        }

        return false;
    }

    public static boolean isFree(Block block, int metadata) {
        if (block == Blocks.air) return true;

        if (FMP_BLOCK.matches(block, metadata)) return true;
        if (AE_BLOCK_CABLE.matches(block, metadata)) return true;

        return false;
    }
}
