package com.recursive_pineapple.matter_manipulator.common.building.schedulable;

import java.util.Iterator;
import java.util.List;

import net.minecraft.world.World;

import com.gtnewhorizon.structurelib.util.XSTR;
import com.recursive_pineapple.matter_manipulator.common.building.BlockSpec;
import com.recursive_pineapple.matter_manipulator.common.building.ImmutableBlockSpec;
import com.recursive_pineapple.matter_manipulator.common.building.PendingBlock;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.Location;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.MMState;
import com.recursive_pineapple.matter_manipulator.common.utils.scheduler.ISchedulable;
import com.recursive_pineapple.matter_manipulator.common.utils.scheduler.WorkScheduler;
import com.recursive_pineapple.matter_manipulator.common.utils.voxels.CubeIterator;
import com.recursive_pineapple.matter_manipulator.common.utils.voxels.CylinderIterator;
import com.recursive_pineapple.matter_manipulator.common.utils.voxels.LineIterator;
import com.recursive_pineapple.matter_manipulator.common.utils.voxels.SphereIterator;

import org.joml.Vector3i;

public class SchedulableGeomAnalyzer implements ISchedulable {

    public final MMState state;
    public final World world;
    public final List<PendingBlock> blocks;

    protected boolean isDone;
    protected XSTR rng;
    protected Iterator<Vector3i> voxelIterator;

    private WorkScheduler scheduler;

    public SchedulableGeomAnalyzer(MMState state, World world, List<PendingBlock> blocks) {
        this.state = state;
        this.world = world;
        this.blocks = blocks;

        if (!Location.areCompatible(state.config.coordA, state.config.coordB) || !state.config.coordA.isInWorld(world)) {
            isDone = true;
            return;
        }

        if (state.config.shape.requiresC()) {
            if (!Location.areCompatible(state.config.coordA, state.config.coordC) || !state.config.coordA.isInWorld(world)) {
                isDone = true;
            }
        }
    }

    @Override
    public boolean hasWork() {
        return !isDone;
    }

    @Override
    public boolean doWork(long tick) {
        if (isDone) return false;

        if (rng == null) {
            rng = new XSTR(state.config.hashCode());
            return true;
        }

        if (voxelIterator == null) {
            int x1 = state.config.coordA.x;
            int y1 = state.config.coordA.y;
            int z1 = state.config.coordA.z;
            int x2 = state.config.coordB.x;
            int y2 = state.config.coordB.y;
            int z2 = state.config.coordB.z;

            int minX = Math.min(x1, x2);
            int minY = Math.min(y1, y2);
            int minZ = Math.min(z1, z2);
            int maxX = Math.max(x1, x2);
            int maxY = Math.max(y1, y2);
            int maxZ = Math.max(z1, z2);

            voxelIterator = switch (state.config.shape) {
                case LINE -> new LineIterator(x1, y1, z1, x2, y2, z2);
                case CUBE -> new CubeIterator(minX, minY, minZ, maxX, maxY, maxZ);
                case SPHERE -> new SphereIterator(minX, minY, minZ, maxX, maxY, maxZ);
                case CYLINDER -> new CylinderIterator(
                    state.config.coordA.toVec(),
                    state.config.coordB.toVec(),
                    state.config.coordC.toVec()
                );
            };

            return true;
        } else if (!voxelIterator.hasNext()) {
            isDone = true;
            return false;
        }

        Vector3i voxel = voxelIterator.next();

        if (voxelIterator instanceof LineIterator) {
            blocks.add(state.config.edges.get(rng).instantiate(state.config.coordA.worldId, voxel.x, voxel.y, voxel.z));
        } else if (voxelIterator instanceof CubeIterator cube) {
            int insideCount = cube.getInsideCount(voxel);

            ImmutableBlockSpec spec = switch (insideCount) {
                case 0 -> state.config.corners.get(rng);
                case 1 -> state.config.edges.get(rng);
                case 2 -> state.config.faces.get(rng);
                case 3 -> state.config.volumes.get(rng);
                default -> BlockSpec.AIR;
            };

            blocks.add(spec.instantiate(state.config.coordA.worldId, voxel.x, voxel.y, voxel.z).setOrders(insideCount, insideCount));
        } else if (voxelIterator instanceof SphereIterator sphere) {
            int order = sphere.getOrder(voxel);
            ImmutableBlockSpec spec = order == 0 ? state.config.faces.get(rng) : state.config.volumes.get(rng);

            blocks.add(spec.instantiate(state.config.coordA.worldId, voxel.x, voxel.y, voxel.z).setOrders(order, order));
        } else if (voxelIterator instanceof CylinderIterator cylinder) {
            int order = cylinder.getOrder(voxel);
            ImmutableBlockSpec spec = switch (order) {
                case 0 -> state.config.faces.get(rng);
                case 1 -> state.config.edges.get(rng);
                case 2 -> state.config.volumes.get(rng);
                default -> BlockSpec.AIR;
            };
            int renderOrder = order == 2 ? 2 : order == 1 ? 1 : 0;
            int buildOrder = order == 2 ? 0 : order == 1 ? 1 : 2;

            blocks.add(spec.instantiate(state.config.coordA.worldId, voxel.x, voxel.y, voxel.z).setOrders(renderOrder, buildOrder));
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
}
