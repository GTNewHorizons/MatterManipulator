package com.recursive_pineapple.matter_manipulator.common.building.fsm;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

import com.recursive_pineapple.matter_manipulator.common.building.PendingBlock;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.Location;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.MMState;
import com.recursive_pineapple.matter_manipulator.common.utils.scheduler.FSM;
import com.recursive_pineapple.matter_manipulator.common.utils.scheduler.ISchedulable;
import com.recursive_pineapple.matter_manipulator.common.utils.scheduler.WorkScheduler;

/**
 * Gets the pending blocks for this manipulator.
 * Note: moving uses a special algorithm, so its value returned here should only be used for drawing the hints.
 */
public class FSMAnalyzer extends FSM<FSMAnalyzer.State, FSMAnalyzer> {

    public enum State {

        INIT_ANALYZER,
        WAIT_FOR_ANALYZER,
        INIT_BLOCK_ITERATOR,
        PROCESS_BLOCK,
        COMPLETE,
        FINISH;

        public static class NAMES {

            public static final String INIT_ANALYZER = "INIT_ANALYZER";
            public static final String WAIT_FOR_ANALYZER = "WAIT_FOR_ANALYZER";
            public static final String INIT_BLOCK_ITERATOR = "INIT_BLOCK_ITERATOR";
            public static final String PROCESS_BLOCK = "PROCESS_BLOCK";
            public static final String COMPLETE = "COMPLETE";
            public static final String FINISH = "FINISH";
        }
    }

    public final MMState state;
    public final EntityPlayer player;
    public final World world;
    public final List<PendingBlock> blocks = new ArrayList<>();

    protected ISchedulable analyzer;
    protected Iterator<PendingBlock> blockIterator;

    public FSMAnalyzer(EntityPlayer player, MMState state, World world) {
        super(State.INIT_ANALYZER, State.FINISH);

        this.state = state;
        this.player = player;
        this.world = world;
    }

    @FSM.Event(State.NAMES.INIT_ANALYZER)
    protected boolean initAnalyzer() {
        analyzer = switch (state.config.placeMode) {
            case COPYING, MOVING -> new FSMAnalysisAnalyzer(state, world, blocks);
            case GEOMETRY -> new FSMGeomAnalyzer(state, world, blocks);
            case EXCHANGING -> new FSMExchangeAnalyzer(state, world, blocks);
            case CABLES -> new FSMCableAnalyzer(state, world, blocks);
        };

        getScheduler().scheduleWork(analyzer);
        return true;
    }

    @FSM.Transition(from = State.NAMES.INIT_ANALYZER, to = State.NAMES.WAIT_FOR_ANALYZER)
    protected boolean analyzerInitialized() {
        return analyzer != null;
    }

    @FSM.Transition(from = State.NAMES.WAIT_FOR_ANALYZER, to = State.NAMES.INIT_BLOCK_ITERATOR)
    protected boolean analyzerComplete() {
        return !analyzer.hasWork();
    }

    @FSM.Event(State.NAMES.INIT_BLOCK_ITERATOR)
    protected boolean initBlockIterator() {
        blockIterator = blocks.iterator();
        return true;
    }

    @FSM.Transition(from = State.NAMES.INIT_BLOCK_ITERATOR, to = State.NAMES.PROCESS_BLOCK)
    protected boolean blockIteratorInitialized() {
        return blockIterator != null;
    }

    @FSM.Event(State.NAMES.PROCESS_BLOCK)
    protected boolean processBlock() {
        if (!blockIterator.hasNext()) return true;

        PendingBlock block = blockIterator.next();
        if (isOutOfRange(block)) {
            blockIterator.remove();
            return true;
        }

        block.generateSortKey();
        return true;
    }

    protected boolean isOutOfRange(PendingBlock block) {
        int maxRange = state.manipulator.tier.maxRange;
        if (maxRange == -1) return false;

        Location playerLocation = new Location(
            player.getEntityWorld(),
            MathHelper.floor_double(player.posX),
            MathHelper.floor_double(player.posY),
            MathHelper.floor_double(player.posZ)
        );

        return block.distanceTo2(playerLocation) > maxRange * maxRange;
    }

    @FSM.Transition(from = State.NAMES.PROCESS_BLOCK, to = State.NAMES.COMPLETE)
    protected boolean blocksProcessed() {
        return !blockIterator.hasNext();
    }

    @FSM.Event(State.NAMES.COMPLETE)
    protected boolean sortBlocks() {
        blocks.sort((a, b) -> a.sortKey.compareTo(b.sortKey));
        return true;
    }

    @FSM.Transition(from = State.NAMES.COMPLETE, to = State.NAMES.FINISH)
    protected boolean sortComplete() {
        return true;
    }

    @Override
    public void onScheduled(WorkScheduler scheduler) {
        if (getScheduler() == scheduler) return;

        super.onScheduled(scheduler);

        if (scheduler == null || !hasWork()) return;
        if (analyzer != null && analyzer.hasWork()) scheduler.scheduleWork(analyzer);
    }

    @Override
    public void onUnscheduled() {
        unscheduleChild(analyzer);
    }
}
