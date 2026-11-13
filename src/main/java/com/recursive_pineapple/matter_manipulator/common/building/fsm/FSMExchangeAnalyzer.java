package com.recursive_pineapple.matter_manipulator.common.building.fsm;

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
import com.recursive_pineapple.matter_manipulator.common.utils.MMUtils;
import com.recursive_pineapple.matter_manipulator.common.utils.scheduler.FSM;
import com.recursive_pineapple.matter_manipulator.common.utils.voxels.BoundingBoxIterator;

import org.joml.Vector3i;

public class FSMExchangeAnalyzer extends FSM<FSMExchangeAnalyzer.State, FSMExchangeAnalyzer> {

    public enum State {

        CHECK_VALIDITY,
        INIT_RANDOM,
        INIT_VOXELS,
        PROCESS_VOXEL,
        FINISH;

        public static class NAMES {

            public static final String CHECK_VALIDITY = "CHECK_VALIDITY";
            public static final String INIT_RANDOM = "INIT_RANDOM";
            public static final String INIT_VOXELS = "INIT_VOXELS";
            public static final String PROCESS_VOXEL = "PROCESS_VOXEL";
            public static final String FINISH = "FINISH";
        }
    }

    public final MMState state;
    public final World world;
    public final List<PendingBlock> blocks;

    protected XSTR rng;
    protected Vector3i deltas;
    protected boolean replacingAir;
    protected BlockSpec existing;
    protected Iterator<Vector3i> voxelIterator;

    public FSMExchangeAnalyzer(MMState state, World world, List<PendingBlock> blocks) {
        super(State.CHECK_VALIDITY, State.FINISH);

        this.state = state;
        this.world = world;
        this.blocks = blocks;

        if (!Location.areCompatible(state.config.coordA, state.config.coordB) || !state.config.coordA.isInWorld(world)) { return; }

        if (state.config.replaceWhitelist == null || state.config.replaceWhitelist.specs.isEmpty()) { return; }

        deltas = MMUtils.getRegionDeltas(state.config.coordA, state.config.coordB);
        replacingAir = state.config.replaceWhitelist.contains(BlockSpec.air());
        existing = new BlockSpec();
    }

    @FSM.Transition(from = State.NAMES.CHECK_VALIDITY, to = State.NAMES.FINISH)
    protected boolean invalidConfiguration() {
        return deltas == null;
    }

    @FSM.Transition(from = State.NAMES.CHECK_VALIDITY, to = State.NAMES.INIT_RANDOM)
    protected boolean validConfiguration() {
        return deltas != null;
    }

    @FSM.Event(State.NAMES.INIT_RANDOM)
    protected boolean initRandom() {
        rng = new XSTR(state.config.hashCode());
        return true;
    }

    @FSM.Transition(from = State.NAMES.INIT_RANDOM, to = State.NAMES.INIT_VOXELS)
    protected boolean randomInitialized() {
        return rng != null;
    }

    @FSM.Event(State.NAMES.INIT_VOXELS)
    protected boolean initVoxels() {
        voxelIterator = new BoundingBoxIterator(state.config.coordA, deltas);
        return true;
    }

    @FSM.Transition(from = State.NAMES.INIT_VOXELS, to = State.NAMES.PROCESS_VOXEL)
    protected boolean voxelsInitialized() {
        return voxelIterator != null;
    }

    @FSM.Event(State.NAMES.PROCESS_VOXEL)
    protected boolean processVoxel() {
        if (!voxelIterator.hasNext()) return true;

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

    @FSM.Transition(from = State.NAMES.PROCESS_VOXEL, to = State.NAMES.FINISH)
    protected boolean voxelsComplete() {
        return !voxelIterator.hasNext();
    }
}
