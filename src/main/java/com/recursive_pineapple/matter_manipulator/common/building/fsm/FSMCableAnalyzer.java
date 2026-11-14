package com.recursive_pineapple.matter_manipulator.common.building.fsm;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import net.minecraftforge.common.util.ForgeDirection;

import gregtech.common.blocks.BlockMachines;

import appeng.api.implementations.parts.IPartCable;
import appeng.api.parts.IPartItem;

import com.recursive_pineapple.matter_manipulator.asm.Optional;
import com.recursive_pineapple.matter_manipulator.common.building.AEAnalysisResult;
import com.recursive_pineapple.matter_manipulator.common.building.AEPartData;
import com.recursive_pineapple.matter_manipulator.common.building.BlockSpec;
import com.recursive_pineapple.matter_manipulator.common.building.GTAnalysisResult;
import com.recursive_pineapple.matter_manipulator.common.building.ImmutableBlockSpec;
import com.recursive_pineapple.matter_manipulator.common.building.InteropConstants;
import com.recursive_pineapple.matter_manipulator.common.building.PendingBlock;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.Location;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.MMState;
import com.recursive_pineapple.matter_manipulator.common.utils.MMUtils;
import com.recursive_pineapple.matter_manipulator.common.utils.Mods;
import com.recursive_pineapple.matter_manipulator.common.utils.scheduler.FSM;

import org.joml.Vector3i;

import it.unimi.dsi.fastutil.objects.Object2ByteOpenHashMap;

public class FSMCableAnalyzer extends FSM<FSMCableAnalyzer.State, FSMCableAnalyzer> {

    public enum State {

        CHECK_VALIDITY,
        INIT_PATH,
        BUILD_CONNECTIONS,
        INIT_VOXELS,
        PROCESS_VOXEL,
        FINISH;

        public static class NAMES {

            public static final String CHECK_VALIDITY = "CHECK_VALIDITY";
            public static final String INIT_PATH = "INIT_PATH";
            public static final String BUILD_CONNECTIONS = "BUILD_CONNECTIONS";
            public static final String INIT_VOXELS = "INIT_VOXELS";
            public static final String PROCESS_VOXEL = "PROCESS_VOXEL";
            public static final String FINISH = "FINISH";
        }
    }

    public final MMState state;
    public final World world;
    public final List<PendingBlock> blocks;

    protected BlockSpec pooled;
    protected Block block;

    protected List<Vector3i> path;
    protected Iterator<Vector3i> voxelIterator;
    protected Object2ByteOpenHashMap<Vector3i> connections;

    public FSMCableAnalyzer(MMState state, World world, List<PendingBlock> blocks) {
        super(State.CHECK_VALIDITY, State.FINISH);

        this.state = state;
        this.world = world;
        this.blocks = blocks;
    }

    @FSM.Transition(from = State.NAMES.CHECK_VALIDITY, to = State.NAMES.FINISH)
    protected boolean invalidCoordinates() {
        return !Location.areCompatible(state.config.coordA, state.config.coordB) || !state.config.coordA.isInWorld(world);
    }

    @FSM.Transition(from = State.NAMES.CHECK_VALIDITY, to = State.NAMES.INIT_PATH)
    protected boolean validCoordinates() {
        return Location.areCompatible(state.config.coordA, state.config.coordB) && state.config.coordA.isInWorld(world);
    }

    @FSM.Event(State.NAMES.INIT_PATH)
    protected boolean initPath() {
        if (state.config.cables == null) {
            pooled = new BlockSpec();
        } else {
            block = Block.getBlockFromItem(state.config.cables.getItem());
        }

        List<Vector3i> points = MMState.getCablePoints(state.config.coordA.toVec(), state.config.cableCorners, state.config.coordB.toVec());
        path = MMState.getCablePath(points);
        return true;
    }

    @FSM.Transition(from = State.NAMES.INIT_PATH, to = State.NAMES.BUILD_CONNECTIONS)
    protected boolean needsConnections() {
        return Mods.GregTech.isModLoaded() && isGTCable(block);
    }

    @FSM.Transition(from = State.NAMES.INIT_PATH, to = State.NAMES.INIT_VOXELS)
    protected boolean doesNotNeedConnections() {
        return !Mods.GregTech.isModLoaded() || !isGTCable(block);
    }

    @FSM.Event(State.NAMES.BUILD_CONNECTIONS)
    protected boolean buildConnections() {
        connections = new Object2ByteOpenHashMap<>();

        for (int i = 0; i < path.size(); i++) {
            Vector3i voxel = path.get(i);
            byte flags = connections.getOrDefault(voxel, (byte) 0);

            for (int j = i - 1; j <= i + 1; j += 2) {
                if (j < 0 || j >= path.size()) continue;

                ForgeDirection dir = MMState.getAxisDirection(voxel, path.get(j));
                if (dir != ForgeDirection.UNKNOWN) flags |= dir.flag;
            }

            connections.put(voxel, flags);
        }

        return true;
    }

    @FSM.Transition(from = State.NAMES.BUILD_CONNECTIONS, to = State.NAMES.INIT_VOXELS)
    protected boolean connectionsBuilt() {
        return connections != null;
    }

    @FSM.Event(State.NAMES.INIT_VOXELS)
    protected boolean initVoxels() {
        voxelIterator = new LinkedHashSet<>(path).iterator();
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

        if (state.config.cables == null) {
            if (Mods.AppliedEnergistics2.isModLoaded()) {
                if (MMUtils.getAECable(pooled, world, voxel.x, voxel.y, voxel.z)) {
                    PendingBlock pendingBlock = InteropConstants.AE_BLOCK_CABLE.toSpec().instantiate(world, voxel.x, voxel.y, voxel.z);

                    pendingBlock.analyze(world.getTileEntity(voxel.x, voxel.y, voxel.z), PendingBlock.ANALYZE_ALL);

                    state.clearAECable(pendingBlock);

                    blocks.add(pendingBlock);

                    return true;
                }
            }

            blocks.add(BlockSpec.AIR.instantiate(world, voxel.x, voxel.y, voxel.z));
        } else if (Mods.AppliedEnergistics2.isModLoaded() && isAECable(state.config.cables)) {
            PendingBlock cable = getAECable(voxel, world, state.config.cables);
            if (cable != null) blocks.add(cable);
        } else if (Mods.OpenComputers.isModLoaded() && isOCCable(block)) {
            blocks.add(getOCCable(voxel, world, state.config.cables));
        } else if (Mods.GregTech.isModLoaded() && isGTCable(block)) {
            byte mConnections = connections.getByte(voxel);

            PendingBlock cable = getGTCable(voxel, mConnections, state.config.cableColour, world, state.config.cables);
            blocks.add(cable);
        }

        return true;
    }

    @FSM.Transition(from = State.NAMES.PROCESS_VOXEL, to = State.NAMES.FINISH)
    protected boolean voxelsComplete() {
        return !voxelIterator.hasNext();
    }

    @Optional(Mods.Names.APPLIED_ENERGISTICS2)
    public static boolean isAECable(ImmutableBlockSpec spec) {
        return spec.getItem() instanceof IPartItem;
    }

    @Optional(Mods.Names.APPLIED_ENERGISTICS2)
    public static PendingBlock getAECable(Vector3i voxel, World world, ImmutableBlockSpec cableSpec) {
        if (!(cableSpec.getItem() instanceof IPartItem partItem)) return null;
        if (!(partItem.createPartFromItemStack(cableSpec.toStack(1)) instanceof IPartCable cable)) return null;

        int x = voxel.x, y = voxel.y, z = voxel.z;

        AEAnalysisResult ae;

        if (InteropConstants.AE_BLOCK_CABLE.matches(world.getBlock(x, y, z), 0)) {
            ae = AEAnalysisResult.analyze(world.getTileEntity(voxel.x, voxel.y, voxel.z));
        } else {
            ae = new AEAnalysisResult();
        }

        if (ae.mAEParts == null) {
            ae.mAEParts = new AEPartData[7];
        }

        ae.mAEParts[ForgeDirection.UNKNOWN.ordinal()] = new AEPartData(cable);

        ae.mAEColour = cable.getCableColor();

        PendingBlock pendingBlock = cableSpec.instantiate(world, x, y, z);

        pendingBlock.ae = ae;

        return pendingBlock;
    }

    @Optional(Mods.Names.OPEN_COMPUTERS)
    public static boolean isOCCable(Block block) {
        return InteropConstants.OC_CABLE.matches(block, 0);
    }

    @Optional(Mods.Names.OPEN_COMPUTERS)
    public static PendingBlock getOCCable(Vector3i voxel, World world, ImmutableBlockSpec spec) {
        return spec.instantiate(world, voxel.x, voxel.y, voxel.z);
    }

    @Optional(Mods.Names.GREG_TECH_NH)
    public static boolean isGTCable(Block block) {
        return block instanceof BlockMachines;
    }

    @Optional(Mods.Names.GREG_TECH_NH)
    protected static ImmutableBlockSpec LASER_MIRROR_SPEC;

    @Optional(Mods.Names.GREG_TECH_NH)
    public static PendingBlock getGTCable(Vector3i voxel, byte connections, byte colour, World world, ImmutableBlockSpec spec) {
        if (LASER_MIRROR_SPEC == null) {
            LASER_MIRROR_SPEC = MMState.getLaserMirrorSpec();
        }

        TileEntity te = world.getTileEntity(voxel.x, voxel.y, voxel.z);

        GTAnalysisResult gt = GTAnalysisResult.analyze(te);

        if (gt == null) gt = new GTAnalysisResult();

        gt.mGTColour = colour;

        gt.mConnections |= connections;

        ImmutableBlockSpec cableSpec = MMState.isLaserPipe(spec) && MMState.isTurn(connections) ? LASER_MIRROR_SPEC : spec;

        PendingBlock pendingBlock = cableSpec.instantiate(world, voxel.x, voxel.y, voxel.z);

        pendingBlock.gt = gt;

        return pendingBlock;
    }
}
