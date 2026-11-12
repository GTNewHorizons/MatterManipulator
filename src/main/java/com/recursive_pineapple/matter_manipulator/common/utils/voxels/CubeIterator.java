package com.recursive_pineapple.matter_manipulator.common.utils.voxels;

import java.util.Iterator;

import org.joml.Vector3i;

public class CubeIterator implements Iterator<Vector3i> {

    protected final int minX;
    protected final int minY;
    protected final int minZ;
    protected final int maxX;
    protected final int maxY;
    protected final int maxZ;

    protected int x;
    protected int y;
    protected int z;

    public CubeIterator(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;

        x = minX;
        y = minY;
        z = minZ;
    }

    public int getInsideCount(Vector3i voxel) {
        int insideCount = 0;

        if (voxel.x > minX && voxel.x < maxX) insideCount++;
        if (voxel.y > minY && voxel.y < maxY) insideCount++;
        if (voxel.z > minZ && voxel.z < maxZ) insideCount++;

        return insideCount;
    }

    @Override
    public boolean hasNext() {
        return x <= maxX;
    }

    @Override
    public Vector3i next() {
        if (!hasNext()) return null;

        Vector3i voxel = new Vector3i(x, y, z);

        z++;

        if (z > maxZ) {
            z = 0;
            y++;
        }

        if (y > maxY) {
            y = 0;
            x++;
        }

        return voxel;
    }
}
