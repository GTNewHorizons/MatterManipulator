package com.recursive_pineapple.matter_manipulator.common.utils.voxels;

import java.util.Iterator;

import org.joml.Vector3i;

public class LineIterator implements Iterator<Vector3i> {

    protected boolean first = true;

    protected int x1;
    protected int y1;
    protected int z1;
    protected int x2;
    protected int y2;
    protected int z2;

    protected final int dx;
    protected final int dy;
    protected final int dz;
    protected final int sx;
    protected final int sy;
    protected final int sz;

    protected int p1;
    protected int p2;

    public LineIterator(int x1, int y1, int z1, int x2, int y2, int z2) {
        this.x1 = x1;
        this.y1 = y1;
        this.z1 = z1;
        this.x2 = x2;
        this.y2 = y2;
        this.z2 = z2;

        dx = Math.abs(x1 - x2);
        dy = Math.abs(y1 - y2);
        dz = Math.abs(z1 - z2);
        sx = x1 < x2 ? 1 : -1;
        sy = y1 < y2 ? 1 : -1;
        sz = z1 < z2 ? 1 : -1;

        if (dx >= dy && dx >= dz) {
            p1 = 2 * dy - dx;
            p2 = 2 * dz - dx;
        } else if (dy >= dx && dy >= dz) {
            p1 = 2 * dx - dy;
            p2 = 2 * dz - dy;
        } else {
            p1 = 2 * dy - dz;
            p2 = 2 * dx - dz;
        }
    }

    @Override
    public boolean hasNext() {
        if (first) return true;

        if (dx >= dy && dx >= dz) {
            return x1 != x2;
        } else if (dy >= dx && dy >= dz) {
            return y1 != y2;
        } else {
            return z1 != z2;
        }
    }

    @Override
    public Vector3i next() {
        if (first) {
            first = false;
            return new Vector3i(x1, y1, z1);
        }

        if (!hasNext()) return null;

        if (dx >= dy && dx >= dz) {
            x1 += sx;

            if (p1 >= 0) {
                y1 += sy;
                p1 -= 2 * dx;
            }
            if (p2 >= 0) {
                z1 += sz;
                p2 -= 2 * dx;
            }

            p1 += 2 * dy;
            p2 += 2 * dz;

            return new Vector3i(x1, y1, z1);
        } else if (dy >= dx && dy >= dz) {
            y1 += sy;

            if (p1 >= 0) {
                x1 += sx;
                p1 -= 2 * dy;
            }
            if (p2 >= 0) {
                z1 += sz;
                p2 -= 2 * dy;
            }

            p1 += 2 * dx;
            p2 += 2 * dz;

            return new Vector3i(x1, y1, z1);
        } else {
            z1 += sz;

            if (p1 >= 0) {
                y1 += sy;
                p1 -= 2 * dz;
            }
            if (p2 >= 0) {
                x1 += sx;
                p2 -= 2 * dz;
            }

            p1 += 2 * dy;
            p2 += 2 * dx;

            return new Vector3i(x1, y1, z1);
        }
    }
}
