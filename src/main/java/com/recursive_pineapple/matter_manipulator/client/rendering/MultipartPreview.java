package com.recursive_pineapple.matter_manipulator.client.rendering;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.world.World;

import com.gtnewhorizon.gtnhlib.client.renderer.LocalTessellator;
import com.gtnewhorizon.gtnhlib.client.renderer.TessellatorManager;
import com.gtnewhorizon.gtnhlib.client.renderer.cel.model.quad.ModelQuadView;
import com.gtnewhorizon.gtnhlib.client.renderer.cel.model.quad.ModelQuadViewMutable;
import com.recursive_pineapple.matter_manipulator.MMMod;
import com.recursive_pineapple.matter_manipulator.common.building.MultipartAnalysisResult;
import com.recursive_pineapple.matter_manipulator.common.building.PendingBlock;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.RenderHints.Hint;

import org.lwjgl.opengl.GL11;

import codechicken.lib.render.CCRenderState;
import codechicken.lib.vec.Vector3;
import codechicken.microblock.Microblock;
import codechicken.microblock.MicroblockClient;
import codechicken.multipart.MultiPartRegistry;
import codechicken.multipart.MultipartGenerator$;
import codechicken.multipart.TMultiPart;
import codechicken.multipart.TileMultipart;
import scala.collection.JavaConversions;

/** Captures native multipart geometry on the client thread for a single render hint. */
public final class MultipartPreview extends Hint {

    private static final ThreadLocal<long[]> QUAD_ORDER = ThreadLocal.withInitial(() -> new long[0]);
    private MultipartAnalysisResult multipart;
    private ModelQuadView[] quads;
    private final boolean statusTint;

    /** Native capture is deferred until the selection renderer finishes. */
    public static Hint createHint(PendingBlock block, Block fallback, int meta, short[] tint, boolean statusTint) {
        if (!(block.mp instanceof MultipartAnalysisResult multipart) || multipart.parts == null) return null;
        return new MultipartPreview(multipart, block, fallback, meta, tint, statusTint);
    }

    private MultipartPreview(
        MultipartAnalysisResult multipart,
        PendingBlock block,
        Block fallback,
        int meta,
        short[] tint,
        boolean statusTint
    ) {
        super(block.x, block.y, block.z, fallback, meta, tint);
        this.multipart = multipart;
        this.statusTint = statusTint;
        depthWrite = false;
    }

    @Override
    public void prepare() {
        try {
            quads = capture(Minecraft.getMinecraft().theWorld);
        } catch (RuntimeException | LinkageError e) {
            MMMod.LOG.error("Could not capture multipart preview; using its block hint", e);
        } finally {
            multipart = null;
        }
    }

    @Override
    public int vertexCount() {
        return quads == null ? super.vertexCount() : Math.multiplyExact(quads.length, 4);
    }

    @Override
    public void draw(Tessellator tes, double eyeX, double eyeY, double eyeZ, int originX, int originY, int originZ) {
        if (quads == null) {
            super.draw(tes, eyeX, eyeY, eyeZ, originX, originY, originZ);
            return;
        }
        long[] order = QUAD_ORDER.get();
        if (order.length < quads.length) {
            order = new long[quads.length];
            QUAD_ORDER.set(order);
        }
        // The legacy cube path offsets the eye position by half a block; native meshes use world coordinates.
        for (int q = 0; q < quads.length; q++) {
            ModelQuadView quad = quads[q];
            double cx = 0, cy = 0, cz = 0;
            for (int v = 0; v < 4; v++) {
                cx += quad.getX(v);
                cy += quad.getY(v);
                cz += quad.getZ(v);
            }
            double dx = x + cx * 0.25 - (eyeX + 0.5);
            double dy = y + cy * 0.25 - (eyeY + 0.5);
            double dz = z + cz * 0.25 - (eyeZ + 0.5);
            float distance = (float) (dx * dx + dy * dy + dz * dz);
            order[q] = (long) ~Float.floatToIntBits(distance) << 32 | q & 0xFFFFFFFFL;
        }
        Arrays.sort(order, 0, quads.length);
        for (int q = 0; q < quads.length; q++) {
            ModelQuadView quad = quads[(int) order[q]];
            for (int v = 0; v < 4; v++) {
                int color = quad.getColor(v);
                tes.setColorRGBA(
                    statusTint ? tint[0] : (color & 0xFF) * tint[0] / 255,
                    statusTint ? tint[1] : (color >>> 8 & 0xFF) * tint[1] / 255,
                    statusTint ? tint[2] : (color >>> 16 & 0xFF) * tint[2] / 255,
                    statusTint ? 150 : (color >>> 24) * 150 / 255
                );
                tes.addVertexWithUV(
                    (x - originX) + quad.getX(v),
                    (y - originY) + quad.getY(v),
                    (z - originZ) + quad.getZ(v),
                    quad.getTexU(v),
                    quad.getTexV(v)
                );
            }
        }
    }

    private ModelQuadView[] capture(World world) {
        List<TMultiPart> parts = new ArrayList<>();
        List<Microblock> microblocks = new ArrayList<>();

        for (var data : multipart.parts) {
            if (data == null || data.typeId == null || data.nbt == null) continue;

            TMultiPart part = MultiPartRegistry.loadPart(data.typeId, data.nbt);
            if (part == null) continue;
            part.load(data.nbt);

            if (part instanceof Microblock microblock) {
                Microblock clientPart = microblock.microClass().create(true, microblock.getMaterial());
                clientPart.load(data.nbt);
                microblocks.add(clientPart);
                part = clientPart;
            }
            parts.add(part);
        }

        if (microblocks.isEmpty()) return null;

        var scalaParts = JavaConversions.asScalaBuffer(parts);
        TileMultipart tile = MultipartGenerator$.MODULE$.generateCompositeTile(null, scalaParts, true);
        // With no world attached, loadParts binds all siblings without world-join callbacks or notifications.
        tile.loadParts(scalaParts);

        tile.setWorldObj(world);
        tile.xCoord = x;
        tile.yCoord = y;
        tile.zCoord = z;
        for (Microblock part : microblocks) {
            part.onPartChanged(null);
        }

        LocalTessellator tes = TessellatorManager.enterLocalMode();
        CCRenderState state = CCRenderState.instance();
        List<ModelQuadViewMutable> quads = new ArrayList<>();
        try {
            tes.startDrawing(GL11.GL_QUADS);
            tes.setTranslation(-x, -y, -z);
            Vector3 position = new Vector3(x, y, z);

            for (Microblock part : microblocks) {
                for (int pass = 0; pass < 2; pass++) {
                    if (!part.getIMaterial().canRenderInPass(pass)) continue;
                    state.resetInstance();
                    state.computeLighting = false;
                    state.lightMatrix.locate(world, x, y, z);
                    ((MicroblockClient) part).render(position, pass);
                }
            }
            tes.collectQuads(quads);
        } finally {
            state.resetInstance();
            tes.setTranslation(0, 0, 0);
            TessellatorManager.exitLocalMode();
        }

        // Match MM's half-size hints centred within each block, preserving the multipart layout.
        for (ModelQuadViewMutable quad : quads) {
            for (int v = 0; v < 4; v++) {
                quad.setX(v, quad.getX(v) * 0.5f + 0.25f);
                quad.setY(v, quad.getY(v) * 0.5f + 0.25f);
                quad.setZ(v, quad.getZ(v) * 0.5f + 0.25f);
            }
        }
        return quads.toArray(new ModelQuadView[0]);
    }
}
