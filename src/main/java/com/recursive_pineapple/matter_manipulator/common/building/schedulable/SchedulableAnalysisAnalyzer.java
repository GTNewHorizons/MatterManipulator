package com.recursive_pineapple.matter_manipulator.common.building.schedulable;

import java.util.Iterator;
import java.util.List;

import net.minecraft.world.World;

import com.recursive_pineapple.matter_manipulator.common.building.PendingBlock;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.Location;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.MMState;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.Transform;
import com.recursive_pineapple.matter_manipulator.common.utils.scheduler.ISchedulable;
import com.recursive_pineapple.matter_manipulator.common.utils.scheduler.WorkScheduler;
import com.recursive_pineapple.matter_manipulator.common.utils.voxels.ArraySpanOffsetIterator;

import org.joml.Vector3i;

public class SchedulableAnalysisAnalyzer implements ISchedulable {

    public final World world;
    public final MMState state;
    public final List<PendingBlock> blocks;

    protected boolean isDone;
    protected SchedulableRegionAnalyzer regionAnalyzer;
    protected SchedulableSmartCopyApplyAnalyzer smartCopyApplyAnalyzer;
    protected SchedulableSmartCopyPostAnalyzer smartCopyPostAnalyzer;
    protected Iterator<PendingBlock> blockIterator;

    protected Iterator<Vector3i> spanOffsetIterator;
    protected Iterator<PendingBlock> spanBlockIterator;
    protected List<PendingBlock> blocksSpan;
    protected Vector3i spanOffset;

    private WorkScheduler scheduler;

    public SchedulableAnalysisAnalyzer(MMState state, World world, List<PendingBlock> blocks) {
        this.world = world;
        this.state = state;
        this.blocks = blocks;

        Location coordA = state.config.coordA;
        Location coordB = state.config.coordB;
        Location coordC = state.config.coordC;

        if (!Location.areCompatible(coordA, coordB, coordC) || !coordA.isInWorld(world)) {
            isDone = true;
        }
    }

    @Override
    public boolean hasWork() {
        return !isDone;
    }

    @Override
    public boolean doWork(long tick) {
        if (isDone) return false;

        if (regionAnalyzer == null) {
            Location a = state.config.coordA;
            Location b = state.config.coordB;
            boolean checkTiles = state.config.placeMode == MMState.PlaceMode.COPYING;

            regionAnalyzer = new SchedulableRegionAnalyzer(world, a, b, checkTiles, blocks);
            scheduler.scheduleWork(regionAnalyzer);

            return false;
        } else if (regionAnalyzer.hasWork()) { return false; }

        boolean isCopyMode = state.config.placeMode == MMState.PlaceMode.COPYING;

        if (blockIterator == null) {
            blockIterator = blocks.iterator();
        }

        if (isCopyMode) {
            if (smartCopyApplyAnalyzer == null) {
                smartCopyApplyAnalyzer = new SchedulableSmartCopyApplyAnalyzer(world, state, blocks);
                scheduler.scheduleWork(smartCopyApplyAnalyzer);

                return false;
            } else if (smartCopyApplyAnalyzer.hasWork()) { return false; }

            boolean hasSpan = state.config.arraySpan != null;
            boolean hasTransform = state.config.transform != null;

            if (blockIterator.hasNext()) {
                PendingBlock block = blockIterator.next();

                if (hasTransform) {
                    Transform t = state.config.transform;
                    t.cacheRotation();

                    Vector3i v = t.apply(block.toVec());

                    block.x = v.x;
                    block.y = v.y;
                    block.z = v.z;

                    block.transform(t);
                }

                Location coordC = state.config.coordC;

                block.x += coordC.x;
                block.y += coordC.y;
                block.z += coordC.z;
            } else if (!hasSpan) {
                if (smartCopyPostAnalyzer == null) {
                    smartCopyPostAnalyzer = new SchedulableSmartCopyPostAnalyzer(world, state, blocks, new Vector3i());
                    scheduler.scheduleWork(smartCopyPostAnalyzer);
                    return false;
                } else if (smartCopyPostAnalyzer.hasWork()) { return false; }

                isDone = true;

                if (hasTransform) state.config.transform.uncacheRotation();

                return false;
            } else {
                if (spanOffsetIterator == null) {
                    spanOffsetIterator = new ArraySpanOffsetIterator(state.config.arraySpan, regionAnalyzer.deltas);
                }

                if (spanBlockIterator == null || !spanBlockIterator.hasNext()) {
                    if (spanOffset != null) {
                        if (smartCopyPostAnalyzer == null) {
                            smartCopyPostAnalyzer = new SchedulableSmartCopyPostAnalyzer(world, state, blocks, new Vector3i());
                            scheduler.scheduleWork(smartCopyPostAnalyzer);
                            return false;
                        } else if (smartCopyPostAnalyzer.hasWork()) { return false; }

                        smartCopyPostAnalyzer = null;
                    }

                    if (!spanOffsetIterator.hasNext()) {
                        isDone = true;

                        blocks.clear();
                        blocks.addAll(blocksSpan);

                        if (hasTransform) state.config.transform.uncacheRotation();

                        return false;
                    }

                    spanOffset = spanOffsetIterator.next();
                    spanBlockIterator = blocks.iterator();
                }

                PendingBlock original = spanBlockIterator.next();
                PendingBlock dup = original.clone();

                dup.x += spanOffset.x;
                dup.y += spanOffset.y;
                dup.z += spanOffset.z;

                blocksSpan.add(dup);
            }
        } else {
            Location coordC = state.config.coordC;

            blocks.forEach(block -> {
                block.x += coordC.x;
                block.y += coordC.y;
                block.z += coordC.z;
            });

            isDone = true;
            return false;
        }

        return true;
    }

    @Override
    public void setScheduler(WorkScheduler scheduler) {
        if (this.scheduler != scheduler) {
            this.scheduler = scheduler;

            if (scheduler != null && !isDone) {
                if (regionAnalyzer != null && regionAnalyzer.hasWork()) {
                    scheduler.scheduleWork(regionAnalyzer);
                }

                if (smartCopyApplyAnalyzer != null && smartCopyApplyAnalyzer.hasWork()) {
                    scheduler.scheduleWork(smartCopyApplyAnalyzer);
                }

                if (smartCopyPostAnalyzer != null && smartCopyPostAnalyzer.hasWork()) {
                    scheduler.scheduleWork(smartCopyPostAnalyzer);
                }
            }
        }
    }

    @Override
    public WorkScheduler getScheduler() {
        return scheduler;
    }
}
