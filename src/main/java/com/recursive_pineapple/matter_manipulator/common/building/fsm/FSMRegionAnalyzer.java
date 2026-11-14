package com.recursive_pineapple.matter_manipulator.common.building.fsm;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import net.minecraft.world.World;

import com.recursive_pineapple.matter_manipulator.GlobalMMConfig;
import com.recursive_pineapple.matter_manipulator.MMMod;
import com.recursive_pineapple.matter_manipulator.common.building.BlockSpec;
import com.recursive_pineapple.matter_manipulator.common.building.PendingBlock;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.Location;
import com.recursive_pineapple.matter_manipulator.common.utils.MMUtils;
import com.recursive_pineapple.matter_manipulator.common.utils.scheduler.FSM;
import com.recursive_pineapple.matter_manipulator.common.utils.voxels.BoundingBoxIterator;

import org.joml.Vector3i;

public class FSMRegionAnalyzer extends FSM<FSMRegionAnalyzer.State, FSMRegionAnalyzer> {

    public enum State {

        CHECK_VALIDITY,
        INIT_VOXELS,
        PROCESS_VOXEL,
        COMPLETE,
        FINISH;

        public static class NAMES {

            public static final String CHECK_VALIDITY = "CHECK_VALIDITY";
            public static final String INIT_VOXELS = "INIT_VOXELS";
            public static final String PROCESS_VOXEL = "PROCESS_VOXEL";
            public static final String COMPLETE = "COMPLETE";
            public static final String FINISH = "FINISH";
        }
    }

    public final World world;
    public final Location a;
    public final Location b;
    public final boolean checkTiles;
    public final List<PendingBlock> blocks;
    public final Vector3i deltas;

    protected long pre;
    protected Iterator<Vector3i> voxelIterator;

    public FSMRegionAnalyzer(World world, Location a, Location b, boolean checkTiles) {
        this(world, a, b, checkTiles, new ArrayList<>());
    }

    public FSMRegionAnalyzer(World world, Location a, Location b, boolean checkTiles, List<PendingBlock> list) {
        super(State.CHECK_VALIDITY, State.FINISH);

        this.world = world;
        this.a = a;
        this.b = b;
        this.checkTiles = checkTiles;
        this.blocks = list;

        if (a == null || b == null || world.provider.dimensionId != a.worldId || a.worldId != b.worldId) {
            deltas = null;
            return;
        }

        deltas = MMUtils.getRegionDeltas(a, b);
        pre = System.nanoTime();
    }

    @FSM.Transition(from = State.NAMES.CHECK_VALIDITY, to = State.NAMES.FINISH)
    protected boolean invalidCoordinates() {
        return deltas == null;
    }

    @FSM.Transition(from = State.NAMES.CHECK_VALIDITY, to = State.NAMES.INIT_VOXELS)
    protected boolean validCoordinates() {
        return deltas != null;
    }

    @FSM.Event(State.NAMES.INIT_VOXELS)
    protected boolean initVoxels() {
        voxelIterator = new BoundingBoxIterator(a, deltas);
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

    @FSM.Transition(from = State.NAMES.PROCESS_VOXEL, to = State.NAMES.COMPLETE)
    protected boolean voxelsComplete() {
        return !voxelIterator.hasNext();
    }

    @FSM.Event(State.NAMES.COMPLETE)
    protected boolean logCompletion() {
        if (GlobalMMConfig.DebugConfig.debug) {
            MMMod.LOG.info("Analysis took " + (System.nanoTime() - pre) / 1e6 + " ms");
        }
        return true;
    }

    @FSM.Transition(from = State.NAMES.COMPLETE, to = State.NAMES.FINISH)
    protected boolean completionLogged() {
        return true;
    }
}
