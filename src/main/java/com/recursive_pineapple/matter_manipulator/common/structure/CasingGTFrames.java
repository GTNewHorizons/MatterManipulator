package com.recursive_pineapple.matter_manipulator.common.structure;

import static gregtech.api.util.GTStructureUtility.ofFrame;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.block.Block;

import gregtech.api.casing.ICasing;
import gregtech.api.enums.materials.TEBlockShapes;

import com.gtnewhorizon.structurelib.structure.IStructureElement;
import com.ruling_0.materiallib.api.Material;
import com.ruling_0.materiallib.api.MaterialLibAPI;

import org.jetbrains.annotations.NotNull;

public class CasingGTFrames implements ICasing {

    public final Material material;

    private static final Map<Material, CasingGTFrames> FRAMES = new ConcurrentHashMap<>();

    private CasingGTFrames(Material material) {
        this.material = material;
    }

    @Override
    public @NotNull Block getBlock() {
        return MaterialLibAPI.getBlock(TEBlockShapes.frameGt);
    }

    @Override
    public int getBlockMeta() {
        return material.getIndex();
    }

    @Override
    public <T> IStructureElement<T> asElement(CasingElementContext<T> context) {
        return ofFrame(material);
    }

    @Override
    public boolean isTiered() {
        return false;
    }

    @Override
    public int getTextureId() {
        throw new UnsupportedOperationException("CasingGTFrames does not support getTextureId()");
    }

    public static CasingGTFrames forMaterial(Material material) {
        return FRAMES.computeIfAbsent(material, CasingGTFrames::new);
    }
}
