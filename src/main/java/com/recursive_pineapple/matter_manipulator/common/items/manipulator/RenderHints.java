package com.recursive_pineapple.matter_manipulator.common.items.manipulator;

import static com.gtnewhorizon.gtnhlib.bytebuf.MemoryUtilities.*;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.entity.Entity;
import net.minecraft.profiler.Profiler;
import net.minecraft.util.IIcon;

import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.WorldEvent;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;

import com.gtnewhorizon.gtnhlib.client.renderer.LocalTessellator;
import com.gtnewhorizon.gtnhlib.client.renderer.TessellatorManager;
import com.gtnewhorizon.gtnhlib.client.renderer.vertex.DefaultVertexFormat;
import com.gtnewhorizon.gtnhlib.client.renderer.vertex.VertexFormat;
import com.recursive_pineapple.matter_manipulator.MMMod;

import org.joml.Vector3d;
import org.joml.Vector3i;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;

public class RenderHints {

    public static final RenderHints INSTANCE = new RenderHints();

    private static final VertexFormat VBO_FORMAT = DefaultVertexFormat.POSITION_TEXTURE_COLOR;

    private static class RenderState {

        public final ArrayList<Hint> hints;
        public long expiration;
        public boolean depthTest = false;

        public RenderState(ArrayList<Hint> hints) {
            this.hints = hints;
        }
    }

    private static class TessellationResult {

        public Vector3i vboOffset;
        public long dataPtr;
        public int dataSize;

        public TessellationResult(Vector3i vboOffset, long dataPtr, int dataSize) {
            this.vboOffset = vboOffset;
            this.dataPtr = dataPtr;
            this.dataSize = dataSize;
        }
    }

    /// The most recent batch of hints. This is not used by the renderer, and it must be flushed by calling [#finish()].
    /// This must only be accessed by the client thread.
    private RenderState pending = null;

    /// The latest batch of hints. This is not sorted in any way and can only be accessed by the client thread.
    /// The contents of this object's hint list can only be accessed by the worker thread, but the list reference itself
    /// and the RenderState object can only be accessed by the client. The worker thread receives a reference to the
    /// hint list, but this field can be replaced arbitrarily.
    private RenderState hints = null;

    /// The player position for the most recent buffer. If the player moves too far, it will cause the quads to be
    /// re-sorted.
    private final Vector3i lastPlayerPosition = new Vector3i();

    /// True when the hints have changed and the VBO needs to be rebuilt from scratch
    private boolean vboNeedsRebuild = false;

    private final ExecutorService workerThread = Executors.newFixedThreadPool(1);
    private Future<TessellationResult> buildTask;

    private TessellationResult lastResult;
    private StreamingVertexBuffer vbo;

    public RenderHints() {
        MinecraftForge.EVENT_BUS.register(this);
    }

    public void start() {
        pending = new RenderState(new ArrayList<>());
    }

    public void setDepthTest(boolean depthTest) {
        pending.depthTest = depthTest;
    }

    public void setExpiry(Duration duration) {
        pending.expiration = System.currentTimeMillis() + duration.toMillis();
    }

    public void finish() {
        hints = pending;
        pending = null;

        vboNeedsRebuild = true;
    }

    public void reset() {
        if (buildTask != null) {
            buildTask.cancel(true);
            buildTask = null;
        }

        pending = null;
        hints = null;

        vboNeedsRebuild = false;

        if (vbo != null) {
            vbo.orphan();
        }
    }

    public void addHint(int x, int y, int z, Block block, int meta, short[] tint) {
        Hint hint = new Hint();

        hint.x = x;
        hint.y = y;
        hint.z = z;
        hint.icons = new IIcon[6];
        hint.tint = tint;

        for (int i = 0; i < 6; i++) {
            hint.icons[i] = block.getIcon(i, meta);
        }

        pending.hints.add(hint);
    }

    @SubscribeEvent
    public void onWorldLoad(WorldEvent.Load e) {
        if (e.world.isRemote) {
            reset();
        }
    }

    private TessellationResult buildVBO(ArrayList<Hint> hints, Vector3d eyes, Vector3i worldPos) {
        hints.sort(Comparator.comparingDouble(info -> -eyes.distanceSquared(info.x, info.y, info.z)));

        final VertexFormat format = VBO_FORMAT;
        final int vertexSize = format.getVertexSize();

        final LocalTessellator tes = TessellatorManager.enterLocalMode();
        tes.startDrawing(GL11.GL_QUADS);

        final int hintCount = hints.size();

        int capacity = hintCount * 6 * 4 * format.getVertexSize();
        final int quadSize = tes.getDataSize(vertexSize);

        long basePtr = nmemAllocChecked(capacity);
        long writePtr = basePtr;
        long endPtr = writePtr + capacity;

        for (int i = 0; i < hintCount; i++) {
            hints.get(i).draw(tes, eyes.x, eyes.y, eyes.z, worldPos.x, worldPos.y, worldPos.z);

            if (writePtr + quadSize > endPtr) {
                capacity = Math.max(capacity + quadSize, (int) (capacity * 1.5));

                long offset = writePtr - basePtr;

                basePtr = nmemReallocChecked(basePtr, capacity);

                writePtr = basePtr + offset;
                endPtr = writePtr + capacity;
            }

            writePtr = tes.writeToBuffer0(writePtr, format);
        }

        tes.exitLocalMode();

        final int dataSize = (int) (writePtr - basePtr);

        return new TessellationResult(new Vector3i(worldPos), basePtr, dataSize);
    }

    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent e) {
        if (hints != null && hints.expiration > 0 && System.currentTimeMillis() >= hints.expiration) {
            hints = null;
        }

        if (hints == null || hints.hints.isEmpty()) return;

        Profiler p = Minecraft.getMinecraft().mcProfiler;

        p.startSection("Render MM Hints");

        Entity player = Minecraft.getMinecraft().renderViewEntity;
        double xd = player.lastTickPosX + (player.posX - player.lastTickPosX) * e.partialTicks;
        double yd = player.lastTickPosY + (player.posY - player.lastTickPosY) * e.partialTicks;
        double zd = player.lastTickPosZ + (player.posZ - player.lastTickPosZ) * e.partialTicks;

        Vector3i worldPos = new Vector3i((int) xd, (int) yd, (int) zd);

        // Subtract by 0.5 because Hint stores the corner coordinates
        final Vector3d eyes = new Vector3d(xd - 0.5f, yd - 0.5f, zd - 0.5f);

        if (vbo == null) {
            vbo = new StreamingVertexBuffer(VBO_FORMAT, GL11.GL_QUADS);
        }

        if (vbo.getVertexCount() > 0) {
            p.startSection("Draw MM Hints");

            GL11.glPushMatrix();
            GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT);

            Minecraft.getMinecraft().renderEngine.bindTexture(TextureMap.locationBlocksTexture);

            // vboOffset is the integer player pos at build time; translate by how much the player has drifted since
            GL11.glTranslated(lastResult.vboOffset.x - xd, lastResult.vboOffset.y - yd, lastResult.vboOffset.z - zd);

            // we need the back facing rendered because the thing is transparent
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glEnable(GL11.GL_BLEND); // enable blend so it is transparent
            GL11.glBlendFunc(GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_SRC_ALPHA);

            if (!hints.depthTest) {
                GL11.glDisable(GL11.GL_DEPTH_TEST);
            } else {
                GL11.glEnable(GL11.GL_DEPTH_TEST);
            }

            vbo.render();

            GL11.glPopAttrib();
            GL11.glPopMatrix();

            p.endSection();
        }

        if (buildTask != null && buildTask.isDone()) {
            lastResult = null;

            try {
                lastResult = buildTask.get();
            } catch (InterruptedException | ExecutionException | CancellationException ex) {
                MMMod.LOG.error("Could not assemble render hint quads", ex);
            }

            buildTask = null;

            if (lastResult != null) {
                lastPlayerPosition.set(lastResult.vboOffset);

                final int vertexCount = lastResult.dataSize / VBO_FORMAT.getVertexSize();
                vbo.allocate(vertexCount, GL15.GL_STREAM_DRAW);
                final ByteBuffer buffer = vbo.map(GL30.GL_MAP_WRITE_BIT | GL30.GL_MAP_INVALIDATE_BUFFER_BIT);

                if (buffer == null) {
                    MMMod.LOG.error(
                        "Could not upload hint VBO: glMapBufferRange returned null (vertexCount={})",
                        vertexCount
                    );
                } else {
                    memCopy(lastResult.dataPtr, memAddress0(buffer), lastResult.dataSize);
                    vbo.unmap();
                }

                nmemFree(lastResult.dataPtr);
            }
        }

        if (buildTask == null && (vboNeedsRebuild || worldPos.distance(lastPlayerPosition) > 1.0)) {
            vboNeedsRebuild = false;

            buildTask = workerThread.submit(() -> buildVBO(this.hints.hints, eyes, worldPos));
        }

        p.endSection();
    }

    private static class Hint {

        public int x, y, z;
        public IIcon[] icons;
        public short[] tint;

        public void draw(
            Tessellator tes,
            double eyeX,
            double eyeY,
            double eyeZ,
            int eyeXint,
            int eyeYint,
            int eyeZint
        ) {
            double size = 0.5;

            tes.setColorRGBA(tint[0], tint[1], tint[2], 150);

            double X = (x - eyeXint) + 0.25;
            double Y = (y - eyeYint) + 0.25;
            double Z = (z - eyeZint) + 0.25;
            double worldX = x + 0.25;
            double worldY = y + 0.25;
            double worldZ = z + 0.25;

            // this rendering code is independently written by glee8e on July 10th, 2023
            // and is released as part of StructureLib under LGPL terms, just like everything else in this project
            // cube is a very special model. its facings can be rendered correctly by viewer distance without using
            // surface normals and view vector
            // here we do a 2 pass render.
            // first pass we draw obstructed faces (i.e. faces that are further away from player)
            // second pass we draw unobstructed faces
            for (int j = 0; j < 2; j++) {
                boolean unobstructedPass = j == 1;
                for (int i = 0; i < 6; i++) {
                    if (icons[i] == null) continue;

                    double u = icons[i].getMinU();
                    double U = icons[i].getMaxU();
                    double v = icons[i].getMinV();
                    double V = icons[i].getMaxV();

                    switch (i) { // {DOWN, UP, NORTH, SOUTH, WEST, EAST}
                        case 0 -> {
                            // all these ifs is in form if ((is face unobstructed) != (is in unobstructred pass))
                            if ((worldY >= eyeY) != unobstructedPass) continue;
                            tes.addVertexWithUV(X, Y, Z, u, v);
                            tes.addVertexWithUV(X + size, Y, Z, U, v);
                            tes.addVertexWithUV(X + size, Y, Z + size, U, V);
                            tes.addVertexWithUV(X, Y, Z + size, u, V);
                        }
                        case 1 -> {
                            if ((worldY + size <= eyeY) != unobstructedPass) continue;
                            tes.addVertexWithUV(X, Y + size, Z, u, v);
                            tes.addVertexWithUV(X, Y + size, Z + size, u, V);
                            tes.addVertexWithUV(X + size, Y + size, Z + size, U, V);
                            tes.addVertexWithUV(X + size, Y + size, Z, U, v);
                        }
                        case 2 -> {
                            if ((worldZ >= eyeZ) != unobstructedPass) continue;
                            tes.addVertexWithUV(X, Y, Z, U, V);
                            tes.addVertexWithUV(X, Y + size, Z, U, v);
                            tes.addVertexWithUV(X + size, Y + size, Z, u, v);
                            tes.addVertexWithUV(X + size, Y, Z, u, V);
                        }
                        case 3 -> {
                            if ((worldZ + size <= eyeZ) != unobstructedPass) continue;
                            tes.addVertexWithUV(X + size, Y, Z + size, U, V);
                            tes.addVertexWithUV(X + size, Y + size, Z + size, U, v);
                            tes.addVertexWithUV(X, Y + size, Z + size, u, v);
                            tes.addVertexWithUV(X, Y, Z + size, u, V);
                        }
                        case 4 -> {
                            if ((worldX >= eyeX) != unobstructedPass) continue;
                            tes.addVertexWithUV(X, Y, Z + size, U, V);
                            tes.addVertexWithUV(X, Y + size, Z + size, U, v);
                            tes.addVertexWithUV(X, Y + size, Z, u, v);
                            tes.addVertexWithUV(X, Y, Z, u, V);
                        }
                        case 5 -> {
                            if ((worldX + size <= eyeX) != unobstructedPass) continue;
                            tes.addVertexWithUV(X + size, Y, Z, U, V);
                            tes.addVertexWithUV(X + size, Y + size, Z, U, v);
                            tes.addVertexWithUV(X + size, Y + size, Z + size, u, v);
                            tes.addVertexWithUV(X + size, Y, Z + size, u, V);
                        }
                    }
                }
            }
        }
    }
}
