package com.recursive_pineapple.matter_manipulator.common.utils.voxels;

import java.util.Iterator;

import org.joml.Vector3i;

public class SphereIterator implements Iterator<Vector3i> {

    protected final int minX;
    protected final int minY;
    protected final int minZ;
    protected final int maxX;
    protected final int maxY;
    protected final int maxZ;

    protected final int sx;
    protected final int sy;
    protected final int sz;
    protected final double rx;
    protected final double ry;
    protected final double rz;

    protected int x;
    protected int y;
    protected int z;
    protected boolean exhausted;
    protected Vector3i next;

    public SphereIterator(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;

        sx = maxX - minX + 1;
        sy = maxY - minY + 1;
        sz = maxZ - minZ + 1;
        rx = sx / 2.0;
        ry = sy / 2.0;
        rz = sz / 2.0;

        x = minX;
        y = minY;
        z = minZ;
    }

    public int getOrder(Vector3i voxel) {
        if (rx > 1 && (!contains(voxel.x + 1, voxel.y, voxel.z) || !contains(voxel.x - 1, voxel.y, voxel.z))) { return 0; }
        if (ry > 1 && (!contains(voxel.x, voxel.y + 1, voxel.z) || !contains(voxel.x, voxel.y - 1, voxel.z))) { return 0; }
        if (rz > 1 && (!contains(voxel.x, voxel.y, voxel.z + 1) || !contains(voxel.x, voxel.y, voxel.z - 1))) { return 0; }

        return 1;
    }

    protected boolean contains(int x, int y, int z) {
        if (x < minX || x > maxX || y < minY || y > maxY || z < minZ || z > maxZ) { return false; }

        int localX = x - minX;
        int localY = y - minY;
        int localZ = z - minZ;
        double distance = Math.sqrt(
            (rx > 1 ? Math.pow((localX - rx + 0.5) / rx, 2.0) : 0) +
                (ry > 1 ? Math.pow((localY - ry + 0.5) / ry, 2.0) : 0) +
                (rz > 1 ? Math.pow((localZ - rz + 0.5) / rz, 2.0) : 0)
        );

        return distance <= 1;
    }

    protected void findNext() {
        while (next == null && !exhausted) {
            int candidateX = x;
            int candidateY = y;
            int candidateZ = z;

            if (z < maxZ) {
                z++;
            } else {
                z = minZ;
                if (y < maxY) {
                    y++;
                } else {
                    y = minY;
                    if (x < maxX) {
                        x++;
                    } else {
                        exhausted = true;
                    }
                }
            }

            if (contains(candidateX, candidateY, candidateZ)) {
                next = new Vector3i(candidateX, candidateY, candidateZ);
            }
        }
    }

    @Override
    public boolean hasNext() {
        findNext();
        return next != null;
    }

    @Override
    public Vector3i next() {
        if (!hasNext()) return null;

        Vector3i voxel = this.next;
        this.next = null;

        return voxel;
    }
}
