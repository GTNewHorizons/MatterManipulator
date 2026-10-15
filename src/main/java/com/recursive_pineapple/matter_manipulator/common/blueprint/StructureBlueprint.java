package com.recursive_pineapple.matter_manipulator.common.blueprint;

import java.util.List;
import java.util.UUID;

import net.minecraft.world.World;

import com.recursive_pineapple.matter_manipulator.common.building.BlockAnalyzer.RegionAnalysis;
import com.recursive_pineapple.matter_manipulator.common.building.PendingBlock;

import org.joml.Vector3i;

/** A raw, origin-relative snapshot, not a transformed build plan or an item payload. */
public class StructureBlueprint {

    public String id, name;
    public Vector3i deltas;
    public List<PendingBlock> blocks;
    /** Actual snapshot block count, retained when blocks are omitted from library metadata. */
    public int blockCount;
    public int formatVersion = 1;

    /**
     * Call immediately after BlockAnalyzer.analyzeRegion, BEFORE any SmartCopy, rotation, array or offset processing.
     * SmartCopy actions cannot be undone and are rejected. Internal P2P groups become blueprint-local positive tokens;
     * the build caller must remap these to fresh destination frequencies before placement. One-sided P2P groups,
     * absolute wireless targets, AE source identity fields and opaque GT mGTData are discarded. Item/pattern NBT is
     * preserved verbatim, not inspected for links; this is not a general-purpose NBT security boundary.
     */
    public static StructureBlueprint capture(RegionAnalysis analysis, String name) {
        if (analysis == null) throw new IllegalArgumentException("Missing region analysis");

        StructureBlueprint blueprint = new StructureBlueprint();
        blueprint.id = UUID.randomUUID().toString();
        blueprint.name = name;
        blueprint.deltas = analysis.deltas;
        blueprint.blocks = analysis.blocks;

        return BlueprintCodec.portableCopy(blueprint);
    }

    /** Returns an independent snapshot; positions remain relative and no source world is accessed. */
    public RegionAnalysis instantiate(World world) {
        if (world == null || world.provider == null) throw new IllegalArgumentException("Missing target world");
        return instantiate(world.provider.dimensionId);
    }

    // Also permits testing target-dimension rebinding without constructing a live Minecraft world.
    RegionAnalysis instantiate(int dimension) {
        StructureBlueprint copy = BlueprintCodec.copy(this);
        RegionAnalysis analysis = new RegionAnalysis();
        analysis.deltas = copy.deltas;
        analysis.blocks = copy.blocks;
        for (PendingBlock block : analysis.blocks) {
            block.worldId = dimension;
        }
        return analysis;
    }
}
