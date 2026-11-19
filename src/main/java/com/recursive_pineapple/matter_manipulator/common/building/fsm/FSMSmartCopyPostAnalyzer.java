package com.recursive_pineapple.matter_manipulator.common.building.fsm;

import static com.recursive_pineapple.matter_manipulator.common.building.fsm.FSMSmartCopyApplyAnalyzer.getBoundP2PInterfaces;

import java.util.Iterator;
import java.util.List;

import net.minecraft.world.World;

import com.recursive_pineapple.matter_manipulator.asm.Optional;
import com.recursive_pineapple.matter_manipulator.common.building.AEAnalysisResult;
import com.recursive_pineapple.matter_manipulator.common.building.AEPartData;
import com.recursive_pineapple.matter_manipulator.common.building.PendingBlock;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.Location;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.MMState;
import com.recursive_pineapple.matter_manipulator.common.utils.Mods;
import com.recursive_pineapple.matter_manipulator.common.utils.scheduler.FSM;

import org.joml.Vector3i;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

public class FSMSmartCopyPostAnalyzer extends FSM<FSMSmartCopyPostAnalyzer.State, FSMSmartCopyPostAnalyzer> {

    public enum State {

        CHECK_FREQUENCIES,
        INIT_FREQUENCIES,
        INIT_BLOCK_ITERATOR,
        PROCESS_BLOCK,
        FINISH;

        public static class NAMES {

            public static final String CHECK_FREQUENCIES = "CHECK_FREQUENCIES";
            public static final String INIT_FREQUENCIES = "INIT_FREQUENCIES";
            public static final String INIT_BLOCK_ITERATOR = "INIT_BLOCK_ITERATOR";
            public static final String PROCESS_BLOCK = "PROCESS_BLOCK";
            public static final String FINISH = "FINISH";
        }
    }

    public final World world;
    public final MMState mmState;
    public final List<PendingBlock> blocks;
    public final Vector3i spanOffset;

    protected Iterator<PendingBlock> blockIterator;
    protected LongOpenHashSet copiedP2pInputFrequencies;

    public FSMSmartCopyPostAnalyzer(World world, MMState mmState, List<PendingBlock> blocks, Vector3i spanOffset) {
        super(State.CHECK_FREQUENCIES, State.FINISH);

        this.world = world;
        this.mmState = mmState;
        this.blocks = blocks;
        this.spanOffset = spanOffset;
    }

    @FSM.Transition(from = State.NAMES.CHECK_FREQUENCIES, to = State.NAMES.INIT_FREQUENCIES)
    protected boolean needsFrequencyCopy() {
        return Mods.AppliedEnergistics2.isModLoaded() && mmState.config.p2pInterfaceCopyMode == MMState.P2PInterfaceCopyMode.COPY_PATTERNS &&
            copiedP2pInputFrequencies == null;
    }

    @FSM.Transition(from = State.NAMES.CHECK_FREQUENCIES, to = State.NAMES.INIT_BLOCK_ITERATOR)
    protected boolean frequencyCopyNotNeeded() {
        return !Mods.AppliedEnergistics2.isModLoaded() || mmState.config.p2pInterfaceCopyMode != MMState.P2PInterfaceCopyMode.COPY_PATTERNS ||
            copiedP2pInputFrequencies != null;
    }

    @FSM.Event(State.NAMES.INIT_FREQUENCIES)
    protected boolean initFrequencies() {
        copiedP2pInputFrequencies = getCopiedP2pInputFrequencies(blocks);
        return true;
    }

    @FSM.Transition(from = State.NAMES.INIT_FREQUENCIES, to = State.NAMES.INIT_BLOCK_ITERATOR)
    protected boolean frequenciesInitialized() {
        return copiedP2pInputFrequencies != null;
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

        if (Mods.AppliedEnergistics2.isModLoaded() && block.ae instanceof AEAnalysisResult ae && ae.mAEParts != null) {
            if (mmState.config.p2pInterfaceCopyMode == MMState.P2PInterfaceCopyMode.COPY_PATTERNS) {
                postP2pInputNewFrequency(ae.mAEParts, copiedP2pInputFrequencies, mmState.config.coordC, spanOffset);
            }
        }

        return true;
    }

    @FSM.Transition(from = State.NAMES.PROCESS_BLOCK, to = State.NAMES.FINISH)
    protected boolean blocksProcessed() {
        return !blockIterator.hasNext();
    }

    @Optional(Mods.Names.APPLIED_ENERGISTICS2)
    public static LongOpenHashSet getCopiedP2pInputFrequencies(List<PendingBlock> blocks) {
        LongOpenHashSet freqs = new LongOpenHashSet();

        for (PendingBlock block : blocks) {
            if (!(block.ae instanceof AEAnalysisResult ae)) continue;
            if (ae.mAEParts == null) continue;

            for (AEPartData partData : getBoundP2PInterfaces(ae.mAEParts)) {
                if (!partData.mP2POutput) freqs.add(partData.mP2PFreq);
            }
        }

        return freqs;
    }

    @Optional(Mods.Names.APPLIED_ENERGISTICS2)
    public static void postP2pInputNewFrequency(AEPartData[] parts, LongOpenHashSet frequencies, Location dest, Vector3i spanOffset) {
        for (AEPartData partData : getBoundP2PInterfaces(parts)) {
            if (frequencies.contains(partData.mP2PFreq)) {
                partData.mP2PFreq = MMState.getCopiedFrequency(partData.mP2PFreq, dest, spanOffset);
            }
        }
    }
}
