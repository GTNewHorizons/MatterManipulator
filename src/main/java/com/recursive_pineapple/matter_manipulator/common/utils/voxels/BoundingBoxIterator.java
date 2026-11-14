package com.recursive_pineapple.matter_manipulator.common.utils.voxels;

import java.util.Iterator;

import com.recursive_pineapple.matter_manipulator.common.items.manipulator.Location;

import org.joml.Vector3i;

public class BoundingBoxIterator implements Iterator<Vector3i> {

    public final Location loc;
    public final Vector3i deltas;

    protected final int minX;
    protected final int minY;
    protected final int minZ;
    protected final int maxX;
    protected final int maxY;
    protected final int maxZ;
    protected final int dX;
    protected final int dY;
    protected final int dZ;

    protected int x;
    protected int y;
    protected int z;

    public BoundingBoxIterator(Location l, Vector3i deltas) {
        this.loc = l;
        this.deltas = deltas;

        minX = Math.min(l.x, l.x + deltas.x);
        minY = Math.min(l.y, l.y + deltas.y);
        minZ = Math.min(l.z, l.z + deltas.z);
        maxX = Math.max(l.x, l.x + deltas.x) + 1;
        maxY = Math.max(l.y, l.y + deltas.y) + 1;
        maxZ = Math.max(l.z, l.z + deltas.z) + 1;

        dX = maxX - minX;
        dY = maxY - minY;
        dZ = maxZ - minZ;
    }

    @Override
    public boolean hasNext() {
        return y < dY;
    }

    @Override
    public Vector3i next() {
        if (y >= dY) return null;

        Vector3i voxel = new Vector3i(minX + x, minY + y, minZ + z);

        x++;

        if (x >= dX) {
            x = 0;
            z++;
        }

        if (z >= dZ) {
            z = 0;
            y++;
        }

        return voxel;
    }
}
