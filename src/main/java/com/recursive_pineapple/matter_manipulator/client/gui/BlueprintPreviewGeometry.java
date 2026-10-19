package com.recursive_pineapple.matter_manipulator.client.gui;

/** Pure camera, layer and picking math for the blueprint preview; no Minecraft or GL state. */
final class BlueprintPreviewGeometry {

    static final float MIN_ZOOM = 0.15f, MAX_ZOOM = 4f;
    static final float MAX_PITCH = 89f;

    private BlueprintPreviewGeometry() {}

    /** 21 bits per axis: blueprint-local coordinates are far inside the signed +-1,048,575 range. */
    static long pack(int x, int y, int z) {
        return ((long) x & 0x1FFFFF) | (((long) y & 0x1FFFFF) << 21) | (((long) z & 0x1FFFFF) << 42);
    }

    static int unpackX(long key) {
        return signExtend((int) (key & 0x1FFFFF));
    }

    static int unpackY(long key) {
        return signExtend((int) ((key >>> 21) & 0x1FFFFF));
    }

    static int unpackZ(long key) {
        return signExtend((int) ((key >>> 42) & 0x1FFFFF));
    }

    private static int signExtend(int value) {
        return (value << 11) >> 11;
    }

    /** Camera distance at which the structure's bounding sphere fits the narrower field of view. */
    static double fitDistance(int sizeX, int sizeY, int sizeZ, double fovYDegrees, double aspect) {
        double radius = 0.5 * Math.sqrt((double) sizeX * sizeX + (double) sizeY * sizeY + (double) sizeZ * sizeZ);
        double halfY = Math.toRadians(fovYDegrees) / 2;
        double halfX = Math.atan(Math.tan(halfY) * Math.max(aspect, 1e-3));
        return Math.max(1.5, radius / Math.sin(Math.min(halfX, halfY)) * 1.05);
    }

    static float clampPitch(float pitch) {
        return Math.max(-MAX_PITCH, Math.min(MAX_PITCH, pitch));
    }

    static float clampZoom(float zoom) {
        return Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom));
    }

    /** Visible layer count is 1..layers; layers means the whole structure. */
    static int clampLayers(int visible, int layers) {
        return Math.max(1, Math.min(Math.max(1, layers), visible));
    }

    /** World units covered by one screen unit at the camera's focal distance. */
    static double unitsPerPixel(double distance, double fovYDegrees, int viewportHeight) {
        return 2 * distance * Math.tan(Math.toRadians(fovYDegrees) / 2) / Math.max(1, viewportHeight);
    }

    interface VoxelTest {

        boolean test(int x, int y, int z);
    }

    /**
     * First voxel accepted by test along the ray origin + t * direction (t >= 0), within the inclusive voxel bounds.
     *
     * @return {x, y, z} or null when the ray misses every accepted voxel.
     */
    static int[] raycast(
        double ox,
        double oy,
        double oz,
        double dx,
        double dy,
        double dz,
        int minX,
        int minY,
        int minZ,
        int maxX,
        int maxY,
        int maxZ,
        VoxelTest test
    ) {
        if (minX > maxX || minY > maxY || minZ > maxZ) return null;
        double[] origin = {
            ox, oy, oz
        }, direction = {
            dx, dy, dz
        };
        int[] min = {
            minX, minY, minZ
        }, max = {
            maxX, maxY, maxZ
        };
        double enter = 0, exit = Double.POSITIVE_INFINITY;
        for (int axis = 0; axis < 3; axis++) {
            if (Math.abs(direction[axis]) < 1e-12) {
                if (origin[axis] < min[axis] || origin[axis] > max[axis] + 1) return null;
                continue;
            }
            double a = (min[axis] - origin[axis]) / direction[axis];
            double b = (max[axis] + 1 - origin[axis]) / direction[axis];
            enter = Math.max(enter, Math.min(a, b));
            exit = Math.min(exit, Math.max(a, b));
        }
        if (enter > exit) return null;

        int[] cell = new int[3], step = new int[3];
        double[] next = new double[3], delta = new double[3];
        for (int axis = 0; axis < 3; axis++) {
            double position = origin[axis] + direction[axis] * enter;
            cell[axis] = Math.max(min[axis], Math.min(max[axis], (int) Math.floor(position)));
            if (direction[axis] > 0) {
                step[axis] = 1;
                next[axis] = (cell[axis] + 1 - origin[axis]) / direction[axis];
                delta[axis] = 1 / direction[axis];
            } else if (direction[axis] < 0) {
                step[axis] = -1;
                next[axis] = (cell[axis] - origin[axis]) / direction[axis];
                delta[axis] = -1 / direction[axis];
            } else {
                next[axis] = Double.POSITIVE_INFINITY;
                delta[axis] = Double.POSITIVE_INFINITY;
            }
        }

        int limit = (maxX - minX + 1) + (maxY - minY + 1) + (maxZ - minZ + 1);
        for (int i = 0; i <= limit; i++) {
            if (test.test(cell[0], cell[1], cell[2])) return cell.clone();
            int axis = next[0] < next[1] ? (next[0] < next[2] ? 0 : 2) : (next[1] < next[2] ? 1 : 2);
            if (next[axis] > exit) return null;
            cell[axis] += step[axis];
            if (cell[axis] < min[axis] || cell[axis] > max[axis]) return null;
            next[axis] += delta[axis];
        }
        return null;
    }
}
