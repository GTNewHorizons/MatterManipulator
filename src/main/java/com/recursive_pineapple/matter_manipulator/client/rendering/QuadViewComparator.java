package com.recursive_pineapple.matter_manipulator.client.rendering;

import java.util.Comparator;

import com.gtnewhorizon.gtnhlib.client.renderer.quad.QuadView;

import org.joml.Vector3f;

/**
 * Sorts QuadViews according to their distance from the player.
 * It's mostly copied from the vanilla comparator.
 * This can certainly be improved, but it works well enough so I haven't.
 */
public class QuadViewComparator implements Comparator<QuadView> {

    private float pX, pY, pZ;

    public void setOrigin(float x, float y, float z) {
        pX = x;
        pY = y;
        pZ = z;
    }

    @Override
    public int compare(QuadView q1, QuadView q2) {
        Vector3f avg1 = new Vector3f();
        Vector3f avg2 = new Vector3f();

        for (int i = 0; i < 4; i++) {
            avg1.x += q1.getX(i) - pX;
            avg1.y += q1.getY(i) - pY;
            avg1.z += q1.getZ(i) - pZ;
            avg2.x += q2.getX(i) - pX;
            avg2.y += q2.getY(i) - pY;
            avg2.z += q2.getZ(i) - pZ;
        }

        avg1.mul(0.25f);
        avg2.mul(0.25f);

        return Float.compare(avg2.lengthSquared(), avg1.lengthSquared());
    }
}
