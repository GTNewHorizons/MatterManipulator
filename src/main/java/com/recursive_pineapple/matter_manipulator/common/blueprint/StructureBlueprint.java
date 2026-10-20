package com.recursive_pineapple.matter_manipulator.common.blueprint;

import java.util.ArrayList;
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
    public int formatVersion = BlueprintCodec.FORMAT_VERSION;

    /**
     * Call immediately after BlockAnalyzer.analyzeRegion, BEFORE any SmartCopy, rotation, array or offset processing.
     * SmartCopy actions cannot be undone and are rejected. Internal P2P groups become blueprint-local positive tokens;
     * the build caller must remap these to fresh destination frequencies before placement. One-sided P2P groups,
     * absolute wireless targets, AE source identity fields and opaque GT mGTData are discarded. Item/pattern NBT is
     * preserved verbatim except quantum bridge inventories/names, which contain the installed singularity's identity.
     * This is not a general-purpose NBT security boundary.
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
        // Decoded snapshots are already validated. Cloning avoids a JSON round trip on every replan of a large region.
        RegionAnalysis analysis = new RegionAnalysis();
        analysis.deltas = new Vector3i(deltas);
        analysis.blocks = new ArrayList<>(blocks.size());
        for (PendingBlock block : blocks) {
            PendingBlock copy = block.clone();
            copy.worldId = dimension;
            analysis.blocks.add(copy);
        }
        return analysis;
    }
}
