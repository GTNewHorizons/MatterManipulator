package com.recursive_pineapple.matter_manipulator.common.building.schedulable;

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
import com.recursive_pineapple.matter_manipulator.common.utils.scheduler.ISchedulable;
import com.recursive_pineapple.matter_manipulator.common.utils.MMUtils;
import com.recursive_pineapple.matter_manipulator.common.utils.Mods;
import com.recursive_pineapple.matter_manipulator.common.utils.scheduler.WorkScheduler;

import org.joml.Vector3i;

import it.unimi.dsi.fastutil.objects.Object2ByteOpenHashMap;

public class SchedulableCableAnalyzer implements ISchedulable {

    public final MMState state;
    public final World world;
    public final List<PendingBlock> blocks;

    protected boolean isDone;
    protected BlockSpec pooled;
    protected Block block;

    protected List<Vector3i> path;
    protected Iterator<Vector3i> voxelIterator;
    protected Object2ByteOpenHashMap<Vector3i> connections;

    private WorkScheduler scheduler;

    public SchedulableCableAnalyzer(MMState state, World world, List<PendingBlock> blocks) {
        this.state = state;
        this.world = world;
        this.blocks = blocks;

        if (!Location.areCompatible(state.config.coordA, state.config.coordB) || !state.config.coordA.isInWorld(world)) {
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

        if (path == null) {
            if (state.config.cables == null)
                pooled = new BlockSpec();
            else
                block = Block.getBlockFromItem(state.config.cables.getItem());

            List<Vector3i> points = MMState.getCablePoints(state.config.coordA.toVec(), state.config.cableCorners, state.config.coordB.toVec());
            path = MMState.getCablePath(points);

            return true;
        }

        if (Mods.GregTech.isModLoaded() && isGTCable(block) && connections == null) {
            connections = new Object2ByteOpenHashMap<>();

            for (int i = 0; i < path.size(); i++) {
                Vector3i voxel = path.get(i);

                byte flags = connections.getOrDefault(voxel, (byte) 0);

                // the voxels before and after this one
                for (int j = i - 1; j <= i + 1; j += 2) {
                    if (j < 0 || j >= path.size()) continue;

                    ForgeDirection dir = MMState.getAxisDirection(voxel, path.get(j));

                    // UNKNOWN's flag isn't a side, so it must not end up in the connections
                    if (dir != ForgeDirection.UNKNOWN) flags |= dir.flag;
                }

                connections.put(voxel, flags);
            }

            return true;
        }

        if (voxelIterator == null) {
            voxelIterator = new LinkedHashSet<>(path).iterator();

            return true;
        } else if (!voxelIterator.hasNext()) {
            isDone = true;
            return false;
        }

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

    @Override
    public void setScheduler(WorkScheduler scheduler) {
        this.scheduler = scheduler;
    }

    @Override
    public WorkScheduler getScheduler() {
        return scheduler;
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
