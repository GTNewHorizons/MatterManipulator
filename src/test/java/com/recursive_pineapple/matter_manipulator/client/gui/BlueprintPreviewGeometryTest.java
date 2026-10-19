package com.recursive_pineapple.matter_manipulator.client.gui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

class BlueprintPreviewGeometryTest {

    @Test
    void packedCoordinatesRoundTripIncludingNegatives() {
        int[][] positions = { { 0, 0, 0 }, { 1, 64, -1 }, { -1_048_575, 1_048_575, 12345 }, { 255, -300, -32768 } };
        Set<Long> keys = new HashSet<>();
        for (int[] p : positions) {
            long key = BlueprintPreviewGeometry.pack(p[0], p[1], p[2]);
            assertEquals(p[0], BlueprintPreviewGeometry.unpackX(key));
            assertEquals(p[1], BlueprintPreviewGeometry.unpackY(key));
            assertEquals(p[2], BlueprintPreviewGeometry.unpackZ(key));
            keys.add(key);
        }
        assertEquals(positions.length, keys.size());
        assertNotEquals(BlueprintPreviewGeometry.pack(1, 0, 0), BlueprintPreviewGeometry.pack(0, 1, 0));
    }

    @Test
    void fitDistanceKeepsTheBoundingSphereInsideTheNarrowerFieldOfView() {
        for (double aspect : new double[] { 0.5, 1, 2.5 }) {
            for (int[] size : new int[][] { { 1, 1, 1 }, { 3, 5, 3 }, { 32, 1, 32 }, { 1, 128, 1 } }) {
                double distance = BlueprintPreviewGeometry.fitDistance(size[0], size[1], size[2], 30, aspect);
                double radius = 0.5 * Math.sqrt(size[0] * size[0] + size[1] * size[1] + size[2] * size[2]);
                double halfY = Math.toRadians(15);
                double halfX = Math.atan(Math.tan(halfY) * aspect);
                assertTrue(radius / distance <= Math.sin(Math.min(halfX, halfY)) + 1e-9);
            }
        }
        assertTrue(
            BlueprintPreviewGeometry.fitDistance(10, 10, 10, 30, 0.5) > BlueprintPreviewGeometry.fitDistance(10, 10, 10, 30, 2),
            "A tall, narrow viewport must move the camera further away"
        );
    }

    @Test
    void cameraAndLayerValuesAreClamped() {
        assertEquals(89f, BlueprintPreviewGeometry.clampPitch(120));
        assertEquals(-89f, BlueprintPreviewGeometry.clampPitch(-95));
        assertEquals(BlueprintPreviewGeometry.MIN_ZOOM, BlueprintPreviewGeometry.clampZoom(0.01f));
        assertEquals(BlueprintPreviewGeometry.MAX_ZOOM, BlueprintPreviewGeometry.clampZoom(100));
        assertEquals(1, BlueprintPreviewGeometry.clampLayers(0, 5));
        assertEquals(5, BlueprintPreviewGeometry.clampLayers(6, 5));
        assertEquals(3, BlueprintPreviewGeometry.clampLayers(3, 5));
        assertEquals(1, BlueprintPreviewGeometry.clampLayers(4, 0));
    }

    @Test
    void raycastReturnsTheNearestSolidVoxel() {
        Set<Long> solid = new HashSet<>();
        solid.add(BlueprintPreviewGeometry.pack(2, 0, 0));
        solid.add(BlueprintPreviewGeometry.pack(4, 0, 0));
        BlueprintPreviewGeometry.VoxelTest test = (x, y, z) -> solid.contains(BlueprintPreviewGeometry.pack(x, y, z));

        // From the -X side the first hit is x=2; from the +X side it is x=4.
        assertArrayEquals(new int[] { 2, 0, 0 }, BlueprintPreviewGeometry.raycast(-5, 0.5, 0.5, 1, 0, 0, 0, 0, 0, 5, 0, 0, test));
        assertArrayEquals(new int[] { 4, 0, 0 }, BlueprintPreviewGeometry.raycast(10, 0.5, 0.5, -1, 0, 0, 0, 0, 0, 5, 0, 0, test));
        // A diagonal ray through the grid still finds a block.
        assertArrayEquals(
            new int[] { 2, 0, 0 },
            BlueprintPreviewGeometry.raycast(-2, 3, 0.5, 1.5, -0.9, 0, 0, 0, 0, 5, 0, 0, (x, y, z) -> x == 2 && y == 0)
        );
    }

    @Test
    void raycastMissesOutsideBoundsOrBehindTheOrigin() {
        BlueprintPreviewGeometry.VoxelTest all = (x, y, z) -> true;
        assertNull(BlueprintPreviewGeometry.raycast(-5, 3.5, 0.5, 1, 0, 0, 0, 0, 0, 5, 2, 0, all));
        assertNull(BlueprintPreviewGeometry.raycast(-5, 0.5, 0.5, -1, 0, 0, 0, 0, 0, 5, 0, 0, all));
        assertNull(BlueprintPreviewGeometry.raycast(-5, 0.5, 0.5, 1, 0, 0, 0, 0, 0, 5, 0, 0, (x, y, z) -> false));
        assertNull(BlueprintPreviewGeometry.raycast(0, 0, 0, 1, 0, 0, 1, 0, 0, 0, 0, 0, all));
    }

    @Test
    void raycastStartingInsideTheBoundsHitsTheCurrentVoxel() {
        assertArrayEquals(
            new int[] { 1, 1, 1 },
            BlueprintPreviewGeometry.raycast(1.5, 1.5, 1.5, 0, -1, 0, 0, 0, 0, 3, 3, 3, (x, y, z) -> true)
        );
    }
}
