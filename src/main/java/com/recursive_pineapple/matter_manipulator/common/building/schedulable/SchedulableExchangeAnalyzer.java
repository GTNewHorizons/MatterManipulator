package com.recursive_pineapple.matter_manipulator.common.building.schedulable;

import static com.recursive_pineapple.matter_manipulator.common.utils.Mods.AppliedEnergistics2;

import java.util.Iterator;
import java.util.List;

import net.minecraft.world.World;

import com.gtnewhorizon.structurelib.util.XSTR;
import com.recursive_pineapple.matter_manipulator.common.building.BlockSpec;
import com.recursive_pineapple.matter_manipulator.common.building.ImmutableBlockSpec;
import com.recursive_pineapple.matter_manipulator.common.building.InteropConstants;
import com.recursive_pineapple.matter_manipulator.common.building.PendingBlock;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.ItemMatterManipulator;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.Location;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.MMState;
import com.recursive_pineapple.matter_manipulator.common.utils.scheduler.ISchedulable;
import com.recursive_pineapple.matter_manipulator.common.utils.MMUtils;
import com.recursive_pineapple.matter_manipulator.common.utils.scheduler.WorkScheduler;
import com.recursive_pineapple.matter_manipulator.common.utils.voxels.BoundingBoxIterator;

import org.joml.Vector3i;

public class SchedulableExchangeAnalyzer implements ISchedulable {

    public final MMState state;
    public final World world;
    public final List<PendingBlock> blocks;

    protected boolean isDone;
    protected XSTR rng;
    protected Vector3i deltas;
    protected boolean replacingAir;
    protected BlockSpec existing;
    protected Iterator<Vector3i> voxelIterator;

    private WorkScheduler scheduler;

    public SchedulableExchangeAnalyzer(MMState state, World world, List<PendingBlock> blocks) {
        this.state = state;
        this.world = world;
        this.blocks = blocks;

        if (!Location.areCompatible(state.config.coordA, state.config.coordB) || !state.config.coordA.isInWorld(world)) {
            isDone = true;
            return;
        }

        if (state.config.replaceWhitelist == null || state.config.replaceWhitelist.specs.isEmpty()) {
            isDone = true;
            return;
        }

        deltas = MMUtils.getRegionDeltas(state.config.coordA, state.config.coordB);
        replacingAir = state.config.replaceWhitelist.contains(BlockSpec.air());
        existing = new BlockSpec();
    }

    @Override
    public boolean hasWork() {
        return !isDone;
    }

    @Override
    public boolean doWork(long tick) {
        if (isDone) return false;

        if (rng == null) {
            rng = new XSTR(state.config.hashCode());
            return true;
        }

        if (voxelIterator == null) {
            voxelIterator = new BoundingBoxIterator(state.config.coordA, deltas);
            return true;
        } else if (!voxelIterator.hasNext()) {
            isDone = true;
            return false;
        }

        Vector3i voxel = voxelIterator.next();

        int x = voxel.x;
        int y = voxel.y;
        int z = voxel.z;

        if (!replacingAir) {
            if (world.isAirBlock(x, y, z)) return true;
        }

        BlockSpec.fromBlock(existing, world, x, y, z);

        if (!replacingAir) {
            if (existing.isAir()) return true;
        }

        if (AppliedEnergistics2.isModLoaded()) {
            // get ae cable if possible
            MMUtils.getAECable(existing, world, x, y, z);
        }

        if (!state.config.replaceWhitelist.containsEquivalent(existing)) return true;

        ImmutableBlockSpec replacement = state.config.replaceWith.get(rng);
        ImmutableBlockSpec block = replacement;

        if (state.hasCap(ItemMatterManipulator.ALLOW_CABLES)) {
            if (AppliedEnergistics2.isModLoaded() && MMUtils.isAECable(replacement)) {
                block = InteropConstants.AE_BLOCK_CABLE.toSpec();
            }
        }

        PendingBlock rep = block.instantiate(world, x, y, z);

        rep.analyze(world.getTileEntity(x, y, z), PendingBlock.ANALYZE_ALL);
        rep.migrate();

        if (state.hasCap(ItemMatterManipulator.ALLOW_CABLES)) {
            if (AppliedEnergistics2.isModLoaded() && MMUtils.isAECable(replacement)) {
                state.placingAECable(rep, replacement);
            }
        }

        blocks.add(rep);

        return true;
    }

    @Override
    public void setScheduler(WorkScheduler scheduler) {
        this.scheduler = scheduler;
    }

    @Override
    public WorkScheduler getScheduler() {
        return scheduler;
    }
}
