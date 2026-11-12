package com.recursive_pineapple.matter_manipulator.common.building.schedulable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

import com.recursive_pineapple.matter_manipulator.common.building.PendingBlock;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.Location;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.MMState;
import com.recursive_pineapple.matter_manipulator.common.utils.scheduler.ISchedulable;
import com.recursive_pineapple.matter_manipulator.common.utils.scheduler.WorkScheduler;

/**
 * Gets the pending blocks for this manipulator.
 * Note: moving uses a special algorithm, so its value returned here should only be used for drawing the hints.
 */
public class SchedulableAnalyzer implements ISchedulable {

    public final MMState state;
    public final EntityPlayer player;
    public final World world;

    public long lastWorkingTick;
    public final List<PendingBlock> blocks = new ArrayList<>();

    protected boolean isDone;
    protected ISchedulable analyzer;
    protected Iterator<PendingBlock> blockIterator;

    private WorkScheduler scheduler;

    public SchedulableAnalyzer(EntityPlayer player, MMState state, World world) {
        this.state = state;
        this.player = player;
        this.world = world;
    }

    @Override
    public boolean hasWork() {
        return !isDone;
    }

    @Override
    public boolean doWork(long tick) {
        if (isDone) return false;

        lastWorkingTick = tick;

        if (analyzer == null) {
            analyzer = switch (state.config.placeMode) {
                case COPYING, MOVING -> new SchedulableAnalysisAnalyzer(state, world, blocks);
                case GEOMETRY -> new SchedulableGeomAnalyzer(state, world, blocks);
                case EXCHANGING -> new SchedulableExchangeAnalyzer(state, world, blocks);
                case CABLES -> new SchedulableCableAnalyzer(state, world, blocks);
            };

            scheduler.scheduleWork(analyzer);

            return false;
        } else if (analyzer.hasWork()) { return false; }

        if (blockIterator == null) {
            blockIterator = blocks.iterator();
        } else if (!blockIterator.hasNext()) {
            isDone = true;
            blocks.sort((a, b) -> a.sortKey.compareTo(b.sortKey));
            return false;
        }

        PendingBlock block = blockIterator.next();

        if (state.manipulator.tier.maxRange != -1) {
            int maxRange2 = state.manipulator.tier.maxRange * state.manipulator.tier.maxRange;

            Location playerLocation = new Location(
                player.getEntityWorld(),
                MathHelper.floor_double(player.posX),
                MathHelper.floor_double(player.posY),
                MathHelper.floor_double(player.posZ)
            );

            if (block.distanceTo2(playerLocation) > maxRange2) {
                blockIterator.remove();
                return true;
            }
        }

        block.generateSortKey();

        return true;
    }

    @Override
    public void setScheduler(WorkScheduler scheduler) {
        if (this.scheduler != scheduler) {
            this.scheduler = scheduler;

            if (scheduler != null && !isDone) {
                if (analyzer != null && analyzer.hasWork()) {
                    scheduler.scheduleWork(analyzer);
                }
            }
        }
    }

    @Override
    public WorkScheduler getScheduler() {
        return scheduler;
    }
}
