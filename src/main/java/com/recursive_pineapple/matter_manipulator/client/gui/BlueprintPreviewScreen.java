package com.recursive_pineapple.matter_manipulator.client.gui;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.item.ItemStack;
import net.minecraft.util.StatCollector;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

import com.recursive_pineapple.matter_manipulator.MMMod;
import com.recursive_pineapple.matter_manipulator.common.blueprint.BlueprintClientCache;
import com.recursive_pineapple.matter_manipulator.common.blueprint.StructureBlueprint;
import com.recursive_pineapple.matter_manipulator.common.building.PendingBlock;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.ItemMatterManipulator;
import com.recursive_pineapple.matter_manipulator.common.networking.Messages.BlueprintStatus;

import org.lwjgl.BufferUtils;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;
import org.lwjgl.util.glu.GLU;

/** Read-only 3D view of one library blueprint, rebuilt client-side from its snapshot; never selects or builds it. */
@SideOnly(Side.CLIENT)
public class BlueprintPreviewScreen extends GuiScreen {

    private static final int LAYER_DOWN = 1, LAYER_UP = 2, RESET = 3, BACK = 4;
    private static final int TEXT = 0xCCCCCC, MUTED_TEXT = 0x999999, PANEL_BACKGROUND = 0x80333333;
    private static final float FOV = 30f;
    private static final float DEFAULT_YAW = 45f, DEFAULT_PITCH = 30f;
    private static final long BUILD_BUDGET_NANOS = 25_000_000L;

    private enum Phase {
        LOADING,
        BUILDING,
        READY,
        FAILED
    }

    private final GuiScreen parent;
    private final String id, name;
    private Phase phase = Phase.LOADING;
    private String failure = "";
    private int request = -1;
    private long deadline;

    private BlueprintPreviewWorld world;
    private BlueprintPreviewRenderer renderer;
    private int layers = 1, visibleLayers = 1;

    private float yaw = DEFAULT_YAW, pitch = DEFAULT_PITCH, zoom = 1;
    private double panX, panY;
    private int dragButton = -1, lastMouseX, lastMouseY;

    private int left, top, panelWidth, panelHeight;
    private int viewLeft, viewTop, viewWidth, viewHeight;
    private int[] hovered;

    private final FloatBuffer modelMatrix = BufferUtils.createFloatBuffer(16);
    private final FloatBuffer projectionMatrix = BufferUtils.createFloatBuffer(16);
    private final IntBuffer viewport = BufferUtils.createIntBuffer(16);
    private final FloatBuffer unprojected = BufferUtils.createFloatBuffer(3);

    public BlueprintPreviewScreen(GuiScreen parent, String id, String name) {
        this.parent = parent;
        this.id = id;
        this.name = name;
    }

    private static String text(String key, Object... arguments) {
        return StatCollector.translateToLocalFormatted("mm.blueprint." + key, arguments);
    }

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        panelWidth = Math.min(560, width - 24);
        panelHeight = height - 24;
        left = (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;
        viewLeft = left + 10;
        viewTop = top + 32;
        viewWidth = panelWidth - 20;
        viewHeight = panelHeight - 32 - 30;

        int footerY = top + panelHeight - 24;
        buttonList.clear();
        buttonList.add(new BlueprintLibraryScreen.LibraryButton(LAYER_DOWN, viewLeft, footerY, 20, "▼"));
        buttonList.add(new BlueprintLibraryScreen.LibraryButton(LAYER_UP, viewLeft + 96, footerY, 20, "▲"));
        int right = viewLeft + viewWidth;
        buttonList.add(new BlueprintLibraryScreen.LibraryButton(BACK, right - 70, footerY, 70, text("back")));
        buttonList.add(new BlueprintLibraryScreen.LibraryButton(RESET, right - 145, footerY, 72, text("preview_reset")));
        updateButtons();
    }

    private void updateButtons() {
        for (Object object : buttonList) {
            GuiButton button = (GuiButton) object;
            button.enabled = switch (button.id) {
                case LAYER_DOWN -> phase == Phase.READY && visibleLayers > 1;
                case LAYER_UP -> phase == Phase.READY && visibleLayers < layers;
                case RESET -> phase == Phase.READY || phase == Phase.FAILED;
                default -> true;
            };
            if (button.id == RESET) button.displayString = text(phase == Phase.FAILED ? "preview_retry" : "preview_reset");
        }
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (!button.enabled) return;
        switch (button.id) {
            case LAYER_DOWN -> setVisibleLayers(visibleLayers - 1);
            case LAYER_UP -> setVisibleLayers(visibleLayers + 1);
            case RESET -> {
                if (phase == Phase.FAILED)
                    retry();
                else
                    resetView();
            }
            case BACK -> back();
            default -> {}
        }
        updateButtons();
    }

    private void back() {
        mc.displayGuiScreen(parent);
    }

    private void retry() {
        phase = Phase.LOADING;
        failure = "";
        request = -1;
    }

    private void resetView() {
        yaw = DEFAULT_YAW;
        pitch = DEFAULT_PITCH;
        zoom = 1;
        panX = panY = 0;
    }

    private void setVisibleLayers(int next) {
        next = BlueprintPreviewGeometry.clampLayers(next, layers);
        if (next == visibleLayers || world == null) return;
        visibleLayers = next;
        world.setVisibleMaxY(visibleLayers >= layers ? Integer.MAX_VALUE : world.minY + visibleLayers - 1);
        renderer.markDirty();
    }

    @Override
    public void updateScreen() {
        ItemStack held = mc.thePlayer == null ? null : mc.thePlayer.getHeldItem();
        if (
            held == null || !(held.getItem() instanceof ItemMatterManipulator) ||
                !ItemMatterManipulator.getState(held).hasCap(ItemMatterManipulator.ALLOW_COPYING)
        ) {
            mc.displayGuiScreen(null);
            return;
        }
        switch (phase) {
            case LOADING -> load();
            case BUILDING -> build();
            default -> {}
        }
        updateButtons();
    }

    private void load() {
        StructureBlueprint blueprint = BlueprintClientCache.preview(id);
        if (blueprint != null) {
            try {
                world = new BlueprintPreviewWorld(blueprint);
                phase = Phase.BUILDING;
            } catch (RuntimeException e) {
                MMMod.LOG.warn("Could not prepare blueprint preview", e);
                fail(text("preview_failed"));
            }
            return;
        }
        long now = System.currentTimeMillis();
        if (request == -1) {
            request = BlueprintClientCache.fetchPreview(id);
            deadline = now + 15000;
        } else if (now > deadline) {
            fail(text("timeout"));
        }
    }

    private void build() {
        if (!world.build(BUILD_BUDGET_NANOS)) return;
        renderer = new BlueprintPreviewRenderer(world);
        layers = world.maxY - world.minY + 1;
        visibleLayers = layers;
        phase = Phase.READY;
    }

    private void fail(String message) {
        phase = Phase.FAILED;
        failure = message;
        request = -1;
    }

    @Override
    protected void keyTyped(char character, int key) {
        switch (key) {
            case Keyboard.KEY_ESCAPE -> back();
            case Keyboard.KEY_UP, Keyboard.KEY_PRIOR -> setVisibleLayers(visibleLayers + 1);
            case Keyboard.KEY_DOWN, Keyboard.KEY_NEXT -> setVisibleLayers(visibleLayers - 1);
            case Keyboard.KEY_HOME -> setVisibleLayers(layers);
            case Keyboard.KEY_R -> {
                if (phase == Phase.FAILED)
                    retry();
                else
                    resetView();
            }
            default -> {}
        }
        updateButtons();
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        // Raw pixel coordinates: scaled GUI units are too coarse for smooth rotation.
        int x = Mouse.getEventX(), y = Mouse.getEventY();
        int button = Mouse.getEventButton();
        if (button != -1) {
            if (Mouse.getEventButtonState()) {
                if ((button == 0 || button == 1) && phase == Phase.READY && overViewport(x, y)) {
                    dragButton = button;
                    lastMouseX = x;
                    lastMouseY = y;
                }
            } else if (button == dragButton) {
                dragButton = -1;
            }
        } else if (dragButton != -1) {
            int dx = x - lastMouseX, dy = y - lastMouseY;
            lastMouseX = x;
            lastMouseY = y;
            if (dragButton == 0) {
                yaw += dx * 0.4f;
                pitch = BlueprintPreviewGeometry.clampPitch(pitch - dy * 0.4f);
            } else {
                double units = BlueprintPreviewGeometry.unitsPerPixel(distance(), FOV, viewHeight * scaleFactor());
                panX += dx * units;
                panY += dy * units;
            }
        }
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0 && phase == Phase.READY) {
            zoom = BlueprintPreviewGeometry.clampZoom(wheel > 0 ? zoom * 0.9f : zoom / 0.9f);
        }
    }

    private int scaleFactor() {
        return new ScaledResolution(mc, mc.displayWidth, mc.displayHeight).getScaleFactor();
    }

    private boolean overViewport(int pixelX, int pixelY) {
        int scale = scaleFactor();
        int guiX = pixelX / scale, guiY = (mc.displayHeight - pixelY - 1) / scale;
        return guiX >= viewLeft && guiX < viewLeft + viewWidth && guiY >= viewTop && guiY < viewTop + viewHeight;
    }

    private double distance() {
        double aspect = viewWidth / (double) Math.max(1, viewHeight);
        return BlueprintPreviewGeometry.fitDistance(
            world.maxX - world.minX + 1,
            world.maxY - world.minY + 1,
            world.maxZ - world.minZ + 1,
            FOV,
            aspect
        ) * zoom;
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        int right = left + panelWidth;
        BlueprintGuiRendering.panel(left - 1, top - 1, right + 1, top + panelHeight + 1, 0x70666666);
        BlueprintGuiRendering.panel(left, top, right, top + panelHeight, 0xB0000000);
        BlueprintGuiRendering.panel(left, top, right, top + 28, PANEL_BACKGROUND);
        BlueprintGuiRendering.panel(viewLeft, viewTop, viewLeft + viewWidth, viewTop + viewHeight, 0x60000000);

        String details = world == null ?
            "" :
            text("dimensions", world.maxX - world.minX + 1, world.maxY - world.minY + 1, world.maxZ - world.minZ + 1) + "  "
                +
                text("blocks", world.blockCount());
        int detailsWidth = fontRendererObj.getStringWidth(details);
        drawString(
            fontRendererObj,
            fontRendererObj.trimStringToWidth(text("preview_title", name), viewWidth - detailsWidth - 12),
            viewLeft,
            top + 10,
            TEXT
        );
        drawString(fontRendererObj, details, viewLeft + viewWidth - detailsWidth, top + 10, MUTED_TEXT);

        hovered = null;
        if (phase == Phase.READY) {
            try {
                renderStructure(mouseX, mouseY, partialTicks);
            } catch (RuntimeException e) {
                MMMod.LOG.warn("Blueprint preview rendering failed", e);
                renderer.dispose();
                fail(text("preview_failed"));
            }
        }

        String overlay = switch (phase) {
            case LOADING -> text("preview_loading");
            case BUILDING -> text("preview_building", Math.round(world.progress() * 100));
            case FAILED -> failure;
            case READY -> world.isEmpty() ? text("preview_empty") : "";
        };
        if (!overlay.isEmpty()) {
            List<String> lines = fontRendererObj.listFormattedStringToWidth(overlay, viewWidth - 16);
            int y = viewTop + viewHeight / 2 - lines.size() * 5;
            for (String line : lines) {
                drawCenteredString(fontRendererObj, line, viewLeft + viewWidth / 2, y, phase == Phase.FAILED ? 0xFFCC88 : TEXT);
                y += 10;
            }
        }
        if (phase == Phase.READY) {
            String hint = world.failures() > 0 ? text("preview_partial", world.failures()) : text("preview_hint");
            drawCenteredString(
                fontRendererObj,
                fontRendererObj.trimStringToWidth(hint, viewWidth - 8),
                viewLeft + viewWidth / 2,
                viewTop + viewHeight - 11,
                world.failures() > 0 ? 0xFFCC88 : MUTED_TEXT
            );
        }

        String layerLabel = visibleLayers >= layers ? text("preview_all_layers") : text("preview_layer", visibleLayers, layers);
        drawCenteredString(fontRendererObj, fontRendererObj.trimStringToWidth(layerLabel, 72), viewLeft + 58, top + panelHeight - 19, TEXT);

        super.drawScreen(mouseX, mouseY, partialTicks);

        if (hovered != null && dragButton == -1) drawBlockTooltip(mouseX, mouseY);
    }

    private void renderStructure(int mouseX, int mouseY, float partialTicks) {
        int scale = scaleFactor();
        int pixelX = viewLeft * scale, pixelY = mc.displayHeight - (viewTop + viewHeight) * scale;
        int pixelWidth = viewWidth * scale, pixelHeight = viewHeight * scale;
        double distance = distance();
        double centerX = (world.minX + world.maxX + 1) / 2.0;
        double centerY = (world.minY + world.maxY + 1) / 2.0;
        double centerZ = (world.minZ + world.maxZ + 1) / 2.0;

        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPushMatrix();
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        try {
            GL11.glViewport(pixelX, pixelY, pixelWidth, pixelHeight);
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
            GL11.glScissor(pixelX, pixelY, pixelWidth, pixelHeight);
            GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);

            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glLoadIdentity();
            GLU.gluPerspective(FOV, pixelWidth / (float) Math.max(1, pixelHeight), 0.1f, (float) (distance * 4 + 256));
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glLoadIdentity();
            GL11.glTranslated(panX, panY, -distance);
            GL11.glRotatef(pitch, 1, 0, 0);
            GL11.glRotatef(yaw, 0, 1, 0);
            GL11.glTranslated(-centerX, -centerY, -centerZ);

            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthFunc(GL11.GL_LEQUAL);
            GL11.glDepthMask(true);
            GL11.glEnable(GL11.GL_CULL_FACE);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glEnable(GL11.GL_ALPHA_TEST);
            GL11.glAlphaFunc(GL11.GL_GREATER, 0.1f);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glShadeModel(GL11.GL_SMOOTH);
            GL11.glColor4f(1, 1, 1, 1);
            RenderHelper.disableStandardItemLighting();
            mc.entityRenderer.disableLightmap(0);
            mc.getTextureManager().bindTexture(TextureMap.locationBlocksTexture);

            renderer.render(partialTicks);

            if (dragButton == -1 && mouseX >= viewLeft && mouseX < viewLeft + viewWidth && mouseY >= viewTop && mouseY < viewTop + viewHeight) {
                modelMatrix.clear();
                projectionMatrix.clear();
                viewport.clear();
                GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, modelMatrix);
                GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, projectionMatrix);
                GL11.glGetInteger(GL11.GL_VIEWPORT, viewport);
                hovered = pick(Mouse.getX(), Mouse.getY());
                if (hovered != null) renderer.renderHighlight(hovered[0], hovered[1], hovered[2]);
            }
        } finally {
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glPopMatrix();
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glPopMatrix();
            GL11.glPopAttrib();
            GL11.glViewport(0, 0, mc.displayWidth, mc.displayHeight);
        }
    }

    private int[] pick(int pixelX, int pixelY) {
        float[] near = unproject(pixelX, pixelY, 0), far = unproject(pixelX, pixelY, 1);
        if (near == null || far == null) return null;
        int maxY = visibleLayers >= layers ? world.maxY : world.minY + visibleLayers - 1;
        return BlueprintPreviewGeometry.raycast(
            near[0],
            near[1],
            near[2],
            far[0] - near[0],
            far[1] - near[1],
            far[2] - near[2],
            world.minX,
            world.minY,
            world.minZ,
            world.maxX,
            maxY,
            world.maxZ,
            (x, y, z) -> world.entry(x, y, z) != null
        );
    }

    private float[] unproject(int pixelX, int pixelY, float depth) {
        unprojected.clear();
        if (!GLU.gluUnProject(pixelX, pixelY, depth, modelMatrix, projectionMatrix, viewport, unprojected)) return null;
        return new float[] {
            unprojected.get(0), unprojected.get(1), unprojected.get(2)
        };
    }

    private void drawBlockTooltip(int mouseX, int mouseY) {
        PendingBlock block = world.source(hovered[0], hovered[1], hovered[2]);
        List<String> tooltip = new ArrayList<>();
        if (block != null) {
            try {
                tooltip.add(block.getDisplayNameChat().getFormattedText());
            } catch (RuntimeException e) {
                tooltip.add(String.valueOf(block.spec));
            }
        } else {
            tooltip.add(world.getBlock(hovered[0], hovered[1], hovered[2]).getLocalizedName());
        }
        tooltip.add(
            "§7" + text("preview_position", hovered[0] - world.minX, hovered[1] - world.minY, hovered[2] - world.minZ)
        );
        drawHoveringText(tooltip, mouseX, mouseY, fontRendererObj);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    @Override
    public void onGuiClosed() {
        if (renderer != null) renderer.dispose();
        Keyboard.enableRepeatEvents(false);
    }

    public static void onError(int request, String translationKey) {
        if (!(Minecraft.getMinecraft().currentScreen instanceof BlueprintPreviewScreen screen)) return;
        if (screen.phase != Phase.LOADING || screen.request == -1 || screen.request != request) return;
        screen.fail(StatCollector.translateToLocal(translationKey));
        screen.updateButtons();
    }

    public static void onResult(int request, BlueprintStatus result) {
        if (result != BlueprintStatus.OK) onError(request, result.translationKey());
    }
}
