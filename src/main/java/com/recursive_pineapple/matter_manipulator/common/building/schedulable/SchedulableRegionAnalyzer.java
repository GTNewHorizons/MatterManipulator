package com.recursive_pineapple.matter_manipulator.common.building.schedulable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import net.minecraft.world.World;

import com.recursive_pineapple.matter_manipulator.GlobalMMConfig;
import com.recursive_pineapple.matter_manipulator.MMMod;
import com.recursive_pineapple.matter_manipulator.common.building.BlockSpec;
import com.recursive_pineapple.matter_manipulator.common.building.PendingBlock;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.Location;
import com.recursive_pineapple.matter_manipulator.common.utils.scheduler.ISchedulable;
import com.recursive_pineapple.matter_manipulator.common.utils.MMUtils;
import com.recursive_pineapple.matter_manipulator.common.utils.scheduler.WorkScheduler;
import com.recursive_pineapple.matter_manipulator.common.utils.voxels.BoundingBoxIterator;

import org.joml.Vector3i;

public class SchedulableRegionAnalyzer implements ISchedulable {

    public final World world;
    public final Location a;
    public final Location b;
    public final boolean checkTiles;
    public final List<PendingBlock> blocks;
    public final Vector3i deltas;

    protected long pre;
    protected boolean isDone;
    protected Iterator<Vector3i> voxelIterator;

    private WorkScheduler scheduler;

    public SchedulableRegionAnalyzer(World world, Location a, Location b, boolean checkTiles) {
        this(world, a, b, checkTiles, new ArrayList<>());
    }

    public SchedulableRegionAnalyzer(World world, Location a, Location b, boolean checkTiles, List<PendingBlock> list) {
        this.world = world;
        this.a = a;
        this.b = b;
        this.checkTiles = checkTiles;
        this.blocks = list;

        if (a == null || b == null || world.provider.dimensionId != a.worldId || a.worldId != b.worldId) {
            isDone = true;
            deltas = null;
            return;
        }

        deltas = MMUtils.getRegionDeltas(a, b);
        pre = System.nanoTime();
    }

    @Override
    public boolean hasWork() {
        return !isDone;
    }

    @Override
    public boolean doWork(long tick) {
        if (isDone) return false;

        if (voxelIterator == null) {
            voxelIterator = new BoundingBoxIterator(a, deltas);
            return true;
        }

        if (!voxelIterator.hasNext()) {
            isDone = true;

            if (GlobalMMConfig.DebugConfig.debug) {
                MMMod.LOG.info("Analysis took " + (System.nanoTime() - pre) / 1e6 + " ms");
            }

            return false;
        }

        Vector3i voxel = voxelIterator.next();

        BlockSpec spec = BlockSpec.fromBlock(null, world, voxel.x, voxel.y, voxel.z);

        if (spec.skipWhenCopying()) { return true; }

        PendingBlock pending = spec.instantiate(world, voxel.x, voxel.y, voxel.z);

        if (checkTiles) {
            pending.analyze(world.getTileEntity(voxel.x, voxel.y, voxel.z), PendingBlock.ANALYZE_ALL & ~PendingBlock.ANALYZE_ARCH);
        }

        // FMP blocks (multiparts) depend on adjacent blocks for support,
        // so they must be placed after regular blocks.
        if (pending.mp != null) {
            pending.buildOrder = Math.max(pending.buildOrder, 100);
        }

        pending.x -= a.x;
        pending.y -= a.y;
        pending.z -= a.z;

        blocks.add(pending);

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
