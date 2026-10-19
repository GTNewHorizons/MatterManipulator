package com.recursive_pineapple.matter_manipulator.client.gui;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.client.renderer.GLAllocation;
import net.minecraft.client.renderer.RenderBlocks;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.client.renderer.tileentity.TileEntitySpecialRenderer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.AxisAlignedBB;

import net.minecraftforge.client.ForgeHooksClient;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

import com.recursive_pineapple.matter_manipulator.MMMod;

import org.lwjgl.opengl.GL11;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

/** Compiles a preview world into one display list per render pass and draws it with the caller's matrices. */
@SideOnly(Side.CLIENT)
final class BlueprintPreviewRenderer {

    private static Field worldRenderPass;
    private static boolean worldRenderPassResolved;

    private final BlueprintPreviewWorld world;
    private final List<TileEntity> specialTiles = new ArrayList<>();
    /** Blocks and tile renderers that threw once are skipped afterwards instead of breaking every frame. */
    private final LongOpenHashSet brokenBlocks = new LongOpenHashSet();
    private final Set<Class<?>> brokenSpecialRenderers = new HashSet<>();
    private int lists = -1;
    private boolean dirty = true;

    BlueprintPreviewRenderer(BlueprintPreviewWorld world) {
        this.world = world;
    }

    void markDirty() {
        dirty = true;
    }

    void render(float partialTicks) {
        if (lists < 0) lists = GLAllocation.generateDisplayLists(2);
        if (dirty) {
            dirty = false;
            compile();
        }

        GL11.glCallList(lists);

        renderSpecialTiles(partialTicks);

        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDepthMask(false);
        GL11.glCallList(lists + 1);
        GL11.glDepthMask(true);
        GL11.glDisable(GL11.GL_BLEND);
    }

    void renderHighlight(int x, int y, int z) {
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_LINE_BIT | GL11.GL_COLOR_BUFFER_BIT);
        try {
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glLineWidth(2f);
            GL11.glColor4f(1, 1, 1, 0.8f);
            double e = 0.002;
            RenderGlobal.drawOutlinedBoundingBox(AxisAlignedBB.getBoundingBox(x - e, y - e, z - e, x + 1 + e, y + 1 + e, z + 1 + e), -1);
        } finally {
            GL11.glColor4f(1, 1, 1, 1);
            GL11.glPopAttrib();
        }
    }

    void dispose() {
        if (lists >= 0) {
            GLAllocation.deleteDisplayLists(lists);
            lists = -1;
        }
        specialTiles.clear();
    }

    private void compile() {
        specialTiles.clear();
        RenderBlocks renderBlocks = new RenderBlocks(world);
        Tessellator tessellator = Tessellator.instance;
        for (int pass = 0; pass < 2; pass++) {
            GL11.glNewList(lists + pass, GL11.GL_COMPILE);
            setWorldRenderPass(pass);
            try {
                begin(tessellator);
                for (BlueprintPreviewWorld.Entry entry : world.entries()) {
                    if (world.entry(entry.x, entry.y, entry.z) != entry) continue;
                    if (pass == 0 && entry.tile != null && TileEntityRendererDispatcher.instance.hasSpecialRenderer(entry.tile)) {
                        specialTiles.add(entry.tile);
                    }
                    long key = BlueprintPreviewGeometry.pack(entry.x, entry.y, entry.z);
                    if (!entry.block.canRenderInPass(pass) || brokenBlocks.contains(key)) continue;
                    try {
                        renderBlocks.renderBlockByRenderType(entry.block, entry.x, entry.y, entry.z);
                    } catch (Throwable t) {
                        if (brokenBlocks.size() < 5) MMMod.LOG.warn("Blueprint preview could not render {}", entry.block, t);
                        brokenBlocks.add(key);
                        // A renderer may have thrown after finishing the shared tessellator's batch itself.
                        begin(tessellator);
                    }
                }
                end(tessellator);
            } finally {
                setWorldRenderPass(-1);
                GL11.glEndList();
            }
        }
    }

    private static void begin(Tessellator tessellator) {
        try {
            tessellator.startDrawingQuads();
        } catch (IllegalStateException alreadyDrawing) {
            // Keep the vertices already batched for this pass.
        }
    }

    private static void end(Tessellator tessellator) {
        try {
            tessellator.draw();
        } catch (IllegalStateException notDrawing) {
            // Nothing batched.
        }
    }

    private void renderSpecialTiles(float partialTicks) {
        if (specialTiles.isEmpty()) return;
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        try {
            RenderHelper.enableStandardItemLighting();
            for (TileEntity tile : specialTiles) {
                if (brokenSpecialRenderers.contains(tile.getClass())) continue;
                TileEntitySpecialRenderer renderer = TileEntityRendererDispatcher.instance.getSpecialRenderer(tile);
                if (renderer == null) continue;
                GL11.glPushMatrix();
                try {
                    GL11.glColor4f(1, 1, 1, 1);
                    renderer.renderTileEntityAt(tile, tile.xCoord, tile.yCoord, tile.zCoord, partialTicks);
                } catch (Throwable t) {
                    if (brokenSpecialRenderers.add(tile.getClass())) {
                        MMMod.LOG.warn("Blueprint preview disabled tile renderer for {}", tile.getClass().getName(), t);
                    }
                } finally {
                    GL11.glPopMatrix();
                }
            }
        } finally {
            RenderHelper.disableStandardItemLighting();
            GL11.glPopAttrib();
        }
    }

    /** Many block renderers (including Forge's multi-pass blocks) read the pass from Forge's world-render hook. */
    private static void setWorldRenderPass(int pass) {
        if (!worldRenderPassResolved) {
            worldRenderPassResolved = true;
            try {
                worldRenderPass = ForgeHooksClient.class.getDeclaredField("worldRenderPass");
                worldRenderPass.setAccessible(true);
            } catch (ReflectiveOperationException | RuntimeException e) {
                MMMod.LOG.debug("Forge world render pass is unavailable to the blueprint preview", e);
            }
        }
        if (worldRenderPass == null) return;
        try {
            worldRenderPass.setInt(null, pass);
        } catch (ReflectiveOperationException | RuntimeException e) {
            worldRenderPass = null;
        }
    }
}
