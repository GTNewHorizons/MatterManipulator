package com.recursive_pineapple.matter_manipulator.common.building.schedulable;

import static com.recursive_pineapple.matter_manipulator.common.building.schedulable.SchedulableSmartCopyApplyAnalyzer.getBoundP2PInterfaces;

import java.util.Iterator;
import java.util.List;

import net.minecraft.world.World;

import com.recursive_pineapple.matter_manipulator.asm.Optional;
import com.recursive_pineapple.matter_manipulator.common.building.AEAnalysisResult;
import com.recursive_pineapple.matter_manipulator.common.building.AEPartData;
import com.recursive_pineapple.matter_manipulator.common.building.PendingBlock;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.Location;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.MMState;
import com.recursive_pineapple.matter_manipulator.common.utils.scheduler.ISchedulable;
import com.recursive_pineapple.matter_manipulator.common.utils.Mods;
import com.recursive_pineapple.matter_manipulator.common.utils.scheduler.WorkScheduler;

import org.joml.Vector3i;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

public class SchedulableSmartCopyPostAnalyzer implements ISchedulable {

    public final World world;
    public final MMState mmState;
    public final List<PendingBlock> blocks;
    public final Vector3i spanOffset;

    protected boolean isDone;
    protected Iterator<PendingBlock> blockIterator;
    protected LongOpenHashSet copiedP2pInputFrequencies;

    private WorkScheduler scheduler;

    public SchedulableSmartCopyPostAnalyzer(World world, MMState mmState, List<PendingBlock> blocks, Vector3i spanOffset) {
        this.world = world;
        this.mmState = mmState;
        this.blocks = blocks;
        this.spanOffset = spanOffset;
    }

    @Override
    public boolean hasWork() {
        return !isDone;
    }

    @Override
    public boolean doWork(long tick) {
        if (isDone) return false;

        if (Mods.AppliedEnergistics2.isModLoaded() && mmState.config.p2pInterfaceCopyMode == MMState.P2PInterfaceCopyMode.COPY_PATTERNS) {
            if (copiedP2pInputFrequencies == null) {
                copiedP2pInputFrequencies = getCopiedP2pInputFrequencies(blocks);
                return true;
            }
        }

        if (blockIterator == null) {
            blockIterator = blocks.iterator();
            return true;
        } else if (!blockIterator.hasNext()) {
            isDone = true;
            return false;
        }

        PendingBlock block = blockIterator.next();

        if (Mods.AppliedEnergistics2.isModLoaded() && block.ae instanceof AEAnalysisResult ae && ae.mAEParts != null) {
            if (mmState.config.p2pInterfaceCopyMode == MMState.P2PInterfaceCopyMode.COPY_PATTERNS) {
                postP2pInputNewFrequency(ae.mAEParts, copiedP2pInputFrequencies, mmState.config.coordC, spanOffset);
            }
        }

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
