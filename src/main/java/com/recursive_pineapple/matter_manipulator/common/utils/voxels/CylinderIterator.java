package com.recursive_pineapple.matter_manipulator.common.utils.voxels;

import java.util.Iterator;

import com.recursive_pineapple.matter_manipulator.common.items.manipulator.MMState;
import com.recursive_pineapple.matter_manipulator.common.utils.MMUtils;

import org.joml.Vector3i;

public class CylinderIterator implements Iterator<Vector3i> {

    protected final Vector3i origin;
    protected final Vector3i vecA;
    protected final Vector3i vecB;
    protected final Vector3i vecH;
    protected final int absA;
    protected final int absB;
    protected final int absH;
    protected final float rA;
    protected final float rB;

    protected int a;
    protected int b;
    protected int h;
    protected boolean exhausted;
    protected Vector3i next;

    public CylinderIterator(Vector3i coordA, Vector3i coordB, Vector3i coordC) {
        origin = new Vector3i(coordA);

        Vector3i b2 = MMState.pinToPlanes(coordA, coordB);
        Vector3i height = MMState.pinToLine(coordA, b2, coordC).sub(coordA);
        Vector3i delta = new Vector3i(b2).sub(coordA);

        delta.x += MMUtils.signum(delta.x);
        delta.y += MMUtils.signum(delta.y);
        delta.z += MMUtils.signum(delta.z);

        int dA;
        int dB;
        int dH;

        switch (delta.minComponent()) {
            case 0 -> {
                dA = delta.y;
                dB = delta.z;
                dH = height.x;
                vecA = new Vector3i(0, MMUtils.signum(delta.y), 0);
                vecB = new Vector3i(0, 0, MMUtils.signum(delta.z));
                vecH = new Vector3i(MMUtils.signum(height.x), 0, 0);
            }
            case 1 -> {
                dA = delta.x;
                dB = delta.z;
                dH = height.y;
                vecA = new Vector3i(MMUtils.signum(delta.x), 0, 0);
                vecB = new Vector3i(0, 0, MMUtils.signum(delta.z));
                vecH = new Vector3i(0, MMUtils.signum(height.y), 0);
            }
            case 2 -> {
                dA = delta.x;
                dB = delta.y;
                dH = height.z;
                vecA = new Vector3i(MMUtils.signum(delta.x), 0, 0);
                vecB = new Vector3i(0, MMUtils.signum(delta.y), 0);
                vecH = new Vector3i(0, 0, MMUtils.signum(height.z));
            }
            default -> throw new AssertionError();
        }

        absA = Math.abs(dA);
        absB = Math.abs(dB);
        absH = Math.abs(dH) + 1;
        rA = absA / 2f;
        rB = absB / 2f;
    }

    public int getOrder(Vector3i voxel) {
        int dx = voxel.x - origin.x;
        int dy = voxel.y - origin.y;
        int dz = voxel.z - origin.z;
        int a = dx * vecA.x + dy * vecA.y + dz * vecA.z;
        int b = dx * vecB.x + dy * vecB.y + dz * vecB.z;
        int h = dx * vecH.x + dy * vecH.y + dz * vecH.z;

        boolean hasAllSideNeighbors = contains(a - 1, b, h) && contains(a + 1, b, h) && contains(a, b - 1, h) && contains(a, b + 1, h);
        if (!hasAllSideNeighbors) return 0;

        boolean hasBothHeightNeighbors = contains(a, b, h - 1) && contains(a, b, h + 1);

        return hasBothHeightNeighbors ? 2 : 1;
    }

    protected boolean contains(int a, int b, int h) {
        if (a < 0 || a >= absA || b < 0 || b >= absB || h < 0 || h >= absH) return false;

        double distance = Math.pow((a - rA + 0.5) / rA, 2.0) +
            Math.pow((b - rB + 0.5) / rB, 2.0);

        return distance <= 1;
    }

    protected void findNext() {
        while (next == null && !exhausted) {
            int candidateA = a;
            int candidateB = b;
            int candidateH = h;

            if (h + 1 < absH) {
                h++;
            } else {
                h = 0;
                if (b + 1 < absB) {
                    b++;
                } else {
                    b = 0;
                    if (a + 1 < absA) {
                        a++;
                    } else {
                        exhausted = true;
                    }
                }
            }

            if (contains(candidateA, candidateB, candidateH)) {
                next = new Vector3i(
                    candidateA * vecA.x + candidateB * vecB.x + candidateH * vecH.x + origin.x,
                    candidateA * vecA.y + candidateB * vecB.y + candidateH * vecH.y + origin.y,
                    candidateA * vecA.z + candidateB * vecB.z + candidateH * vecH.z + origin.z
                );
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
