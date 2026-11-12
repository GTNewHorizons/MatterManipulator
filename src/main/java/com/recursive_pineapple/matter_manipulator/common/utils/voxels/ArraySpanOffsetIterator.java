package com.recursive_pineapple.matter_manipulator.common.utils.voxels;

import java.util.Iterator;

import org.joml.Vector3i;

public class ArraySpanOffsetIterator implements Iterator<Vector3i> {

    public final Vector3i arraySpan;
    public final Vector3i deltas;

    protected int ax;
    protected int ay;
    protected int az;

    public ArraySpanOffsetIterator(Vector3i arraySpan, Vector3i deltas) {
        this.arraySpan = arraySpan;
        this.deltas = deltas;

        ax = Math.min(arraySpan.x, 0);
        ay = Math.min(arraySpan.y, 0);
        az = Math.min(arraySpan.z, 0);
    }

    @Override
    public boolean hasNext() {
        return ay <= Math.max(arraySpan.y, 0);
    }

    @Override
    public Vector3i next() {
        if (ay > Math.max(arraySpan.y, 0)) return null;

        int dx = ax * (deltas.x + (deltas.x < 0 ? -1 : 1));
        int dy = ay * (deltas.y + (deltas.y < 0 ? -1 : 1));
        int dz = az * (deltas.z + (deltas.z < 0 ? -1 : 1));

        ax++;

        if (ax > Math.max(arraySpan.x, 0)) {
            ax = Math.min(arraySpan.x, 0);
            az++;
        }

        if (az > Math.max(arraySpan.z, 0)) {
            az = Math.min(arraySpan.z, 0);
            ay++;
        }

        return new Vector3i(dx, dy, dz);
    }
}
