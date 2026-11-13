package com.recursive_pineapple.matter_manipulator.common.building.fsm;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import net.minecraft.world.World;

import com.recursive_pineapple.matter_manipulator.common.building.PendingBlock;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.Location;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.MMState;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.Transform;
import com.recursive_pineapple.matter_manipulator.common.utils.scheduler.FSM;
import com.recursive_pineapple.matter_manipulator.common.utils.scheduler.WorkScheduler;
import com.recursive_pineapple.matter_manipulator.common.utils.voxels.ArraySpanOffsetIterator;

import org.joml.Vector3i;

public class FSMAnalysisAnalyzer extends FSM<FSMAnalysisAnalyzer.State, FSMAnalysisAnalyzer> {

    public enum State {

        CHECK_VALIDITY,
        INIT_REGION,
        WAIT_REGION,
        INIT_BLOCK_ITERATOR,
        SELECT_MODE,
        NON_COPY,
        INIT_SMART_COPY,
        WAIT_SMART_COPY,
        PROCESS_BLOCK,
        INIT_SPAN,
        ADVANCE_SPAN,
        PROCESS_SPAN,
        INIT_BASE_POST,
        WAIT_BASE_POST,
        COMPLETE_BASE,
        INIT_SPAN_POST,
        WAIT_SPAN_POST,
        COMPLETE_SPAN,
        FINISH;

        public static class NAMES {

            public static final String CHECK_VALIDITY = "CHECK_VALIDITY";
            public static final String INIT_REGION = "INIT_REGION";
            public static final String WAIT_REGION = "WAIT_REGION";
            public static final String INIT_BLOCK_ITERATOR = "INIT_BLOCK_ITERATOR";
            public static final String SELECT_MODE = "SELECT_MODE";
            public static final String NON_COPY = "NON_COPY";
            public static final String INIT_SMART_COPY = "INIT_SMART_COPY";
            public static final String WAIT_SMART_COPY = "WAIT_SMART_COPY";
            public static final String PROCESS_BLOCK = "PROCESS_BLOCK";
            public static final String INIT_SPAN = "INIT_SPAN";
            public static final String ADVANCE_SPAN = "ADVANCE_SPAN";
            public static final String PROCESS_SPAN = "PROCESS_SPAN";
            public static final String INIT_BASE_POST = "INIT_BASE_POST";
            public static final String WAIT_BASE_POST = "WAIT_BASE_POST";
            public static final String COMPLETE_BASE = "COMPLETE_BASE";
            public static final String INIT_SPAN_POST = "INIT_SPAN_POST";
            public static final String WAIT_SPAN_POST = "WAIT_SPAN_POST";
            public static final String COMPLETE_SPAN = "COMPLETE_SPAN";
            public static final String FINISH = "FINISH";
        }
    }

    public final World world;
    public final MMState state;
    public final List<PendingBlock> blocks;

    protected FSMRegionAnalyzer regionAnalyzer;
    protected FSMSmartCopyApplyAnalyzer smartCopyApplyAnalyzer;
    protected FSMSmartCopyPostAnalyzer smartCopyPostAnalyzer;

    protected Iterator<PendingBlock> blockIterator;
    protected Iterator<Vector3i> spanOffsetIterator;
    protected Iterator<PendingBlock> spanBlockIterator;
    protected List<PendingBlock> blocksSpan;
    protected Vector3i spanOffset;

    public FSMAnalysisAnalyzer(MMState state, World world, List<PendingBlock> blocks) {
        super(State.CHECK_VALIDITY, State.FINISH);

        this.world = world;
        this.state = state;
        this.blocks = blocks;
    }

    @FSM.Transition(from = State.NAMES.CHECK_VALIDITY, to = State.NAMES.FINISH)
    protected boolean invalidCoordinates() {
        Location coordA = state.config.coordA;
        return !Location.areCompatible(coordA, state.config.coordB, state.config.coordC) || !coordA.isInWorld(world);
    }

    @FSM.Transition(from = State.NAMES.CHECK_VALIDITY, to = State.NAMES.INIT_REGION)
    protected boolean validCoordinates() {
        return !invalidCoordinates();
    }

    @FSM.Event(State.NAMES.INIT_REGION)
    protected boolean initRegionAnalyzer() {
        boolean checkTiles = state.config.placeMode == MMState.PlaceMode.COPYING;
        regionAnalyzer = new FSMRegionAnalyzer(world, state.config.coordA, state.config.coordB, checkTiles, blocks);
        getScheduler().scheduleWork(regionAnalyzer);
        return true;
    }

    @FSM.Transition(from = State.NAMES.INIT_REGION, to = State.NAMES.WAIT_REGION)
    protected boolean regionStarted() {
        return regionAnalyzer != null;
    }

    @FSM.Transition(from = State.NAMES.WAIT_REGION, to = State.NAMES.INIT_BLOCK_ITERATOR)
    protected boolean regionComplete() {
        return !regionAnalyzer.hasWork();
    }

    @FSM.Event(State.NAMES.INIT_BLOCK_ITERATOR)
    protected boolean initBlockIterator() {
        blockIterator = blocks.iterator();
        return true;
    }

    @FSM.Transition(from = State.NAMES.INIT_BLOCK_ITERATOR, to = State.NAMES.SELECT_MODE)
    protected boolean blockIteratorInitialized() {
        return blockIterator != null;
    }

    @FSM.Transition(from = State.NAMES.SELECT_MODE, to = State.NAMES.INIT_SMART_COPY)
    protected boolean isCopyMode() {
        return state.config.placeMode == MMState.PlaceMode.COPYING;
    }

    @FSM.Transition(from = State.NAMES.SELECT_MODE, to = State.NAMES.NON_COPY)
    protected boolean isNotCopyMode() {
        return state.config.placeMode != MMState.PlaceMode.COPYING;
    }

    @FSM.Event(State.NAMES.NON_COPY)
    protected boolean applyDestinationOffset() {
        Location coordC = state.config.coordC;
        blocks.forEach(block -> {
            block.x += coordC.x;
            block.y += coordC.y;
            block.z += coordC.z;
        });
        return true;
    }

    @FSM.Transition(from = State.NAMES.NON_COPY, to = State.NAMES.FINISH)
    protected boolean nonCopyComplete() {
        return true;
    }

    @FSM.Event(State.NAMES.INIT_SMART_COPY)
    protected boolean initSmartCopyAnalyzer() {
        smartCopyApplyAnalyzer = new FSMSmartCopyApplyAnalyzer(world, state, blocks);
        getScheduler().scheduleWork(smartCopyApplyAnalyzer);
        return true;
    }

    @FSM.Transition(from = State.NAMES.INIT_SMART_COPY, to = State.NAMES.WAIT_SMART_COPY)
    protected boolean smartCopyStarted() {
        return smartCopyApplyAnalyzer != null;
    }

    @FSM.Transition(from = State.NAMES.WAIT_SMART_COPY, to = State.NAMES.PROCESS_BLOCK)
    protected boolean smartCopyComplete() {
        return !smartCopyApplyAnalyzer.hasWork();
    }

    @FSM.Event(State.NAMES.PROCESS_BLOCK)
    protected boolean processBlock() {
        if (!blockIterator.hasNext()) return true;

        PendingBlock block = blockIterator.next();
        Transform transform = state.config.transform;

        if (transform != null) {
            transform.cacheRotation();
            Vector3i v = transform.apply(block.toVec());
            block.x = v.x;
            block.y = v.y;
            block.z = v.z;
            block.transform(transform);
        }

        Location coordC = state.config.coordC;
        block.x += coordC.x;
        block.y += coordC.y;
        block.z += coordC.z;

        return true;
    }

    @FSM.Transition(from = State.NAMES.PROCESS_BLOCK, to = State.NAMES.INIT_BASE_POST)
    protected boolean baseBlocksCompleteWithoutSpan() {
        return state.config.arraySpan == null && !blockIterator.hasNext();
    }

    @FSM.Transition(from = State.NAMES.PROCESS_BLOCK, to = State.NAMES.INIT_SPAN)
    protected boolean baseBlocksCompleteWithSpan() {
        return state.config.arraySpan != null && !blockIterator.hasNext();
    }

    @FSM.Event(State.NAMES.INIT_BASE_POST)
    protected boolean initBasePostAnalyzer() {
        smartCopyPostAnalyzer = new FSMSmartCopyPostAnalyzer(world, state, blocks, new Vector3i());
        getScheduler().scheduleWork(smartCopyPostAnalyzer);

        return true;
    }

    @FSM.Transition(from = State.NAMES.INIT_BASE_POST, to = State.NAMES.WAIT_BASE_POST)
    protected boolean basePostStarted() {
        return smartCopyPostAnalyzer != null;
    }

    @FSM.Transition(from = State.NAMES.WAIT_BASE_POST, to = State.NAMES.COMPLETE_BASE)
    protected boolean basePostComplete() {
        return !smartCopyPostAnalyzer.hasWork();
    }

    @FSM.Event(State.NAMES.COMPLETE_BASE)
    protected boolean completeBaseCopy() {
        if (state.config.transform != null) state.config.transform.uncacheRotation();
        return true;
    }

    @FSM.Transition(from = State.NAMES.COMPLETE_BASE, to = State.NAMES.FINISH)
    protected boolean baseCopyFinalized() {
        return true;
    }

    @FSM.Event(State.NAMES.INIT_SPAN)
    protected boolean initSpan() {
        spanOffsetIterator = new ArraySpanOffsetIterator(state.config.arraySpan, regionAnalyzer.deltas);
        blocksSpan = new ArrayList<>();
        return true;
    }

    @FSM.Transition(from = State.NAMES.INIT_SPAN, to = State.NAMES.ADVANCE_SPAN)
    protected boolean spanInitialized() {
        return spanOffsetIterator != null;
    }

    @FSM.Event(State.NAMES.ADVANCE_SPAN)
    protected boolean advanceSpan() {
        smartCopyPostAnalyzer = null;
        spanOffset = spanOffsetIterator.next();
        spanBlockIterator = blocks.iterator();
        return true;
    }

    @FSM.Transition(from = State.NAMES.ADVANCE_SPAN, to = State.NAMES.COMPLETE_SPAN)
    protected boolean spansComplete() {
        return spanOffset == null;
    }

    @FSM.Transition(from = State.NAMES.ADVANCE_SPAN, to = State.NAMES.PROCESS_SPAN)
    protected boolean spanAdvanced() {
        return spanOffset != null;
    }

    @FSM.Event(State.NAMES.PROCESS_SPAN)
    protected boolean processSpanBlock() {
        if (!spanBlockIterator.hasNext()) return true;

        PendingBlock original = spanBlockIterator.next();
        PendingBlock dup = original.clone();
        dup.x += spanOffset.x;
        dup.y += spanOffset.y;
        dup.z += spanOffset.z;
        blocksSpan.add(dup);
        return true;
    }

    @FSM.Transition(from = State.NAMES.PROCESS_SPAN, to = State.NAMES.INIT_SPAN_POST)
    protected boolean spanBlocksComplete() {
        return spanBlockIterator != null && !spanBlockIterator.hasNext();
    }

    @FSM.Event(State.NAMES.INIT_SPAN_POST)
    protected boolean initSpanPostAnalyzer() {
        smartCopyPostAnalyzer = new FSMSmartCopyPostAnalyzer(world, state, blocks, new Vector3i());
        getScheduler().scheduleWork(smartCopyPostAnalyzer);
        return true;
    }

    @FSM.Transition(from = State.NAMES.INIT_SPAN_POST, to = State.NAMES.WAIT_SPAN_POST)
    protected boolean spanPostStarted() {
        return smartCopyPostAnalyzer != null;
    }

    @FSM.Transition(from = State.NAMES.WAIT_SPAN_POST, to = State.NAMES.ADVANCE_SPAN)
    protected boolean spanPostComplete() {
        return !smartCopyPostAnalyzer.hasWork();
    }

    @FSM.Event(State.NAMES.COMPLETE_SPAN)
    protected boolean completeSpan() {
        blocks.clear();
        blocks.addAll(blocksSpan);
        if (state.config.transform != null) state.config.transform.uncacheRotation();
        return true;
    }

    @FSM.Transition(from = State.NAMES.COMPLETE_SPAN, to = State.NAMES.FINISH)
    protected boolean spanCopyFinalized() {
        return true;
    }

    @Override
    public void onScheduled(WorkScheduler scheduler) {
        if (getScheduler() == scheduler) return;

        super.onScheduled(scheduler);

        if (scheduler == null || !hasWork()) return;
        if (regionAnalyzer != null && regionAnalyzer.hasWork()) scheduler.scheduleWork(regionAnalyzer);
        if (smartCopyApplyAnalyzer != null && smartCopyApplyAnalyzer.hasWork()) scheduler.scheduleWork(smartCopyApplyAnalyzer);
        if (smartCopyPostAnalyzer != null && smartCopyPostAnalyzer.hasWork()) scheduler.scheduleWork(smartCopyPostAnalyzer);
    }

    @Override
    public void onUnscheduled() {
        unscheduleChild(regionAnalyzer);
        unscheduleChild(smartCopyApplyAnalyzer);
        unscheduleChild(smartCopyPostAnalyzer);
    }
}
