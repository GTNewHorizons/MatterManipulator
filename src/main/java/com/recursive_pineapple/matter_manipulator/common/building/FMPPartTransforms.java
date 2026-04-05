package com.recursive_pineapple.matter_manipulator.common.building;

import java.util.Arrays;

import net.minecraft.nbt.NBTTagCompound;

import net.minecraftforge.common.util.ForgeDirection;

import com.recursive_pineapple.matter_manipulator.common.items.manipulator.Transform;

public class FMPPartTransforms {

    private FMPPartTransforms() {}

    public static void transformSide(NBTTagCompound nbt, Transform transform) {
        if (!nbt.hasKey("side")) return;

        int side = nbt.getByte("side") & 0xFF;
        if (side >= 6) return;

        int newSide = rotateFace(side, transform);
        nbt.setByte("side", (byte) newSide);
    }

    public static void transformMicroblockShape(String typeId, NBTTagCompound nbt, Transform transform) {
        if (typeId == null || !nbt.hasKey("shape")) return;

        int shape = nbt.getByte("shape") & 0xFF;
        int slot = shape & 0x0F;
        int sizeBits = shape & 0xF0;

        int newSlot = switch (typeId) {
            case "mcr_face", "mcr_hllw" -> transformFaceSlot(slot, transform);
            case "mcr_edge" -> transformEdgeSlot(slot, transform);
            case "mcr_cnr" -> transformCornerSlot(slot, transform);
            default -> -1;
        };

        if (newSlot >= 0) {
            nbt.setByte("shape", (byte) (sizeBits | newSlot));
        }
    }

    private static int rotateFace(int faceOrdinal, Transform transform) {
        return transform.apply(ForgeDirection.getOrientation(faceOrdinal)).ordinal();
    }

    private static int transformFaceSlot(int slot, Transform transform) {
        return slot < 6 ? rotateFace(slot, transform) : slot;
    }

    // Edge slot -> two defining faces. From codechicken.multipart.PartMap.
    // @formatter:off
    private static final int[][] EDGE_FACES = {
        {
            2, 4
        }, // 0: north-west vertical edge
        {
            3, 4
        }, // 1: south-west vertical edge
        {
            2, 5
        }, // 2: north-east vertical edge
        {
            3, 5
        }, // 3: south-east vertical edge
        {
            0, 4
        }, // 4: bottom-west horizontal edge (north-south)
        {
            0, 5
        }, // 5: bottom-east horizontal edge (north-south)
        {
            1, 4
        }, // 6: top-west horizontal edge (north-south)
        {
            1, 5
        }, // 7: top-east horizontal edge (north-south)
        {
            0, 2
        }, // 8: bottom-north horizontal edge (east-west)
        {
            1, 2
        }, // 9: top-north horizontal edge (east-west)
        {
            0, 3
        }, // 10: bottom-south horizontal edge (east-west)
        {
            1, 3
        }, // 11: top-south horizontal edge (east-west)
    };
    // @formatter:on

    private static int transformEdgeSlot(int edgeSlot, Transform transform) {
        if (edgeSlot < 0 || edgeSlot >= EDGE_FACES.length) return edgeSlot;

        int f1 = rotateFace(EDGE_FACES[edgeSlot][0], transform);
        int f2 = rotateFace(EDGE_FACES[edgeSlot][1], transform);

        return findEdge(Math.min(f1, f2), Math.max(f1, f2));
    }

    private static int findEdge(int face1, int face2) {
        for (int i = 0; i < EDGE_FACES.length; i++) {
            if (EDGE_FACES[i][0] == face1 && EDGE_FACES[i][1] == face2) return i;
        }
        return -1;
    }

    // Corner slot -> three defining faces. From codechicken.multipart.PartMap.
    // @formatter:off
    private static final int[][] CORNER_FACES = {
        {
            0, 2, 4
        }, // 0: bottom-north-west corner
        {
            1, 2, 4
        }, // 1: top-north-west corner
        {
            0, 3, 4
        }, // 2: bottom-south-west corner
        {
            1, 3, 4
        }, // 3: top-south-west corner
        {
            0, 2, 5
        }, // 4: bottom-north-east corner
        {
            1, 2, 5
        }, // 5: top-north-east corner
        {
            0, 3, 5
        }, // 6: bottom-south-east corner
        {
            1, 3, 5
        }, // 7: top-south-east corner
    };
    // @formatter:on

    private static int transformCornerSlot(int cornerSlot, Transform transform) {
        if (cornerSlot < 0 || cornerSlot >= CORNER_FACES.length) return cornerSlot;

        int[] rotated = {
            rotateFace(CORNER_FACES[cornerSlot][0], transform),
            rotateFace(CORNER_FACES[cornerSlot][1], transform),
            rotateFace(CORNER_FACES[cornerSlot][2], transform),
        };
        Arrays.sort(rotated);

        return findCorner(rotated);
    }

    private static int findCorner(int[] sortedFaces) {
        for (int i = 0; i < CORNER_FACES.length; i++) {
            if (CORNER_FACES[i][0] == sortedFaces[0] && CORNER_FACES[i][1] == sortedFaces[1] && CORNER_FACES[i][2] == sortedFaces[2]) return i;
        }
        return -1;
    }

    // CodeChickenLib Rotation.sideRotMap: maps (side << 2 | rotation) to absolute direction.
    // @formatter:off
    private static final int[] SIDE_ROT_MAP = {
        3,
        4,
        2,
        5, // DOWN: r0=S, r1=W, r2=N, r3=E
        3,
        5,
        2,
        4, // UP: r0=S, r1=E, r2=N, r3=W
        1,
        5,
        0,
        4, // NORTH: r0=U, r1=E, r2=D, r3=W
        1,
        4,
        0,
        5, // SOUTH: r0=U, r1=W, r2=D, r3=E
        1,
        2,
        0,
        3, // WEST: r0=U, r1=N, r2=D, r3=S
        1,
        3,
        0,
        2, // EAST: r0=U, r1=S, r2=D, r3=N
    };
    // @formatter:on

    /** Transforms the "orient" field: (side << 2) | rotation. Used by ProjectRed gates. */
    public static void transformOrient(NBTTagCompound nbt, Transform transform) {
        if (!nbt.hasKey("orient")) return;

        int orient = nbt.getByte("orient") & 0xFF;
        int oldSide = orient >> 2;
        int oldRot = orient & 0x3;

        if (oldSide >= 6) return;

        int newSide = rotateFace(oldSide, transform);

        int frontDir = SIDE_ROT_MAP[oldSide << 2 | oldRot];
        int newFrontDir = rotateFace(frontDir, transform);

        int newRot = 0;
        for (int r = 0; r < 4; r++) {
            if (SIDE_ROT_MAP[newSide << 2 | r] == newFrontDir) {
                newRot = r;
                break;
            }
        }

        nbt.setByte("orient", (byte) ((newSide << 2) | newRot));
    }

    public static void clearConnMap(NBTTagCompound nbt) {
        if (nbt.hasKey("connMap")) {
            nbt.setInteger("connMap", 0);
        }
    }
}
