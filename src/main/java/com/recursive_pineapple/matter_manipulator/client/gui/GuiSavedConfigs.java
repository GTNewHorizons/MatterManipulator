package com.recursive_pineapple.matter_manipulator.client.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.util.StatCollector;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

import com.recursive_pineapple.matter_manipulator.common.items.manipulator.MMConfig;
import com.recursive_pineapple.matter_manipulator.common.networking.Messages;

import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

@SideOnly(Side.CLIENT)
public class GuiSavedConfigs extends GuiScreen {

    // ── Panel ──────────────────────────────────────────────────────────────
    private static final int PANEL_W = 300;
    private static final int PANEL_H = 222;
    private static final int MARGIN = 8;

    // ── List ───────────────────────────────────────────────────────────────
    private static final int LIST_TOP = 22;
    private static final int ENTRY_H = 22; // 20px button + 1px padding each side
    private static final int MAX_VIS = 6;
    private static final int LIST_BOT = LIST_TOP + MAX_VIS * ENTRY_H; // 154
    private static final int SCROLL_STEP = 8; // pixels per mouse-wheel tick

    // ── Columns (panel-relative) ───────────────────────────────────────────
    // text: 4..191 | load: 196..239 | delete: 244..287 | scrollbar: 292..296
    private static final int COL_LOAD = 196;
    private static final int COL_DEL = 244;
    private static final int COL_SB = 292;
    private static final int SB_W = 5;

    // MAX_VIS + 1 slots so a partial entry is visible at the bottom while scrolling
    private static final int SLOT_COUNT = MAX_VIS + 1;

    // ── Button IDs ─────────────────────────────────────────────────────────
    private static final int ID_LOAD = 100; // 100..106
    private static final int ID_DEL = 200; // 200..206
    private static final int ID_SAVE = 300;
    private static final int ID_CLOSE = 301;

    // ── State ──────────────────────────────────────────────────────────────
    private final List<String> names = new ArrayList<>();
    private int pixelScroll = 0;
    private GuiTextField nameField;
    private boolean isDraggingScrollbar = false;
    private int scrollbarDragOffsetY = 0;

    public GuiSavedConfigs(Map<String, MMConfig> savedConfigs) {
        if (savedConfigs != null) {
            names.addAll(savedConfigs.keySet());
        }
    }

    private int px() {
        return (width - PANEL_W) / 2;
    }

    private int py() {
        return (height - PANEL_H) / 2;
    }

    private int viewportH() {
        return LIST_BOT - LIST_TOP;
    }

    private int maxScroll() {
        return Math.max(0, names.size() * ENTRY_H - viewportH());
    }

    private int slotOffset() {
        return pixelScroll % ENTRY_H;
    }

    private int entryForSlot(int slot) {
        return pixelScroll / ENTRY_H + slot;
    }

    // ── Init ───────────────────────────────────────────────────────────────

    @Override
    public void initGui() {
        buttonList.clear();
        int px = px(), py = py();

        for (int slot = 0; slot < SLOT_COUNT; slot++) {
            buttonList.add(new GuiButton(ID_LOAD + slot, 0, 0, 44, 20, ""));
            buttonList.add(new GuiButton(ID_DEL + slot, 0, 0, 44, 20, ""));
        }

        int btnY = py + PANEL_H - MARGIN - 20;
        buttonList.add(
            new GuiButton(
                ID_SAVE,
                px + MARGIN,
                btnY,
                80,
                20,
                StatCollector.translateToLocal("mm.gui.saved_configs.save")
            )
        );
        buttonList.add(
            new GuiButton(
                ID_CLOSE,
                px + PANEL_W - MARGIN - 80,
                btnY,
                80,
                20,
                StatCollector.translateToLocal("mm.gui.saved_configs.close")
            )
        );

        nameField = new GuiTextField(
            fontRendererObj,
            px + MARGIN,
            py + LIST_BOT + 16,
            PANEL_W - MARGIN * 2,
            16
        );
        nameField.setMaxStringLength(64);
        nameField.setFocused(true);

        rebuildRowButtons();
    }

    /**
     * Repositions and shows/hides all row buttons based on the current pixelScroll.
     */
    private void rebuildRowButtons() {
        int px = px(), py = py();
        int offset = slotOffset();
        String loadLabel = StatCollector.translateToLocal("mm.gui.saved_configs.load");
        String delLabel = StatCollector.translateToLocal("mm.gui.saved_configs.delete");

        for (int slot = 0; slot < SLOT_COUNT; slot++) {
            int entryIdx = entryForSlot(slot);
            int ry = py + LIST_TOP + slot * ENTRY_H - offset;
            boolean exists = entryIdx < names.size();
            boolean inView = ry + ENTRY_H > py + LIST_TOP && ry < py + LIST_BOT;

            GuiButton load = findButton(ID_LOAD + slot);
            load.xPosition = px + COL_LOAD;
            load.yPosition = ry + 1;
            load.enabled = exists;
            load.visible = inView && exists;
            load.displayString = loadLabel;

            GuiButton del = findButton(ID_DEL + slot);
            del.xPosition = px + COL_DEL;
            del.yPosition = ry + 1;
            del.enabled = exists;
            del.visible = inView && exists;
            del.displayString = delLabel;
        }
    }

    private GuiButton findButton(int id) {
        for (GuiButton b : buttonList) {
            if (b.id == id) { return b; }
        }
        throw new IllegalStateException("Button " + id + " not found");
    }

    private void clampScroll() {
        pixelScroll = Math.max(0, Math.min(pixelScroll, maxScroll()));
    }

    // ── Scrollbar ──────────────────────────────────────────────────────────

    private int sbTrackH() {
        return LIST_BOT - 2 - LIST_TOP;
    } // 4px gap from each divider

    private int sbThumbH() {
        int totalH = names.size() * ENTRY_H;
        if (totalH <= viewportH()) { return sbTrackH(); }
        return Math.max(10, sbTrackH() * viewportH() / totalH);
    }

    private int sbThumbY(int py) {
        int maxS = maxScroll();
        if (maxS <= 0) { return py + LIST_TOP; }
        int travel = sbTrackH() - sbThumbH();
        if (travel <= 0) { return py + LIST_TOP; }
        return py + LIST_TOP + pixelScroll * travel / maxS;
    }

    private void setScrollFromThumbY(int thumbAbsY, int py) {
        int travel = sbTrackH() - sbThumbH();
        if (travel <= 0) {
            pixelScroll = 0;
            return;
        }
        pixelScroll = (thumbAbsY - (py + LIST_TOP)) * maxScroll() / travel;
        clampScroll();
        rebuildRowButtons();
    }

    private boolean handleScrollbarClick(int mouseX, int mouseY) {
        if (maxScroll() <= 0) { return false; }
        int px = px(), py = py();
        int sbX = px + COL_SB;
        if (mouseX < sbX || mouseX >= sbX + SB_W) { return false; }
        int thumbY = sbThumbY(py);
        int thumbH = sbThumbH();
        if (mouseY >= thumbY && mouseY < thumbY + thumbH) {
            isDraggingScrollbar = true;
            scrollbarDragOffsetY = mouseY - thumbY;
        } else if (mouseY >= py + LIST_TOP && mouseY < py + LIST_BOT - 2) {
            setScrollFromThumbY(mouseY - thumbH / 2, py);
        }
        return true;
    }

    // ── Actions ────────────────────────────────────────────────────────────

    @Override
    protected void actionPerformed(GuiButton button) {
        int id = button.id;
        if (id >= ID_LOAD && id < ID_LOAD + SLOT_COUNT) {
            int idx = entryForSlot(id - ID_LOAD);
            if (idx < names.size()) {
                Messages.LoadConfig.sendToServer(names.get(idx));
                mc.displayGuiScreen(null);
            }
        } else if (id >= ID_DEL && id < ID_DEL + SLOT_COUNT) {
            int idx = entryForSlot(id - ID_DEL);
            if (idx < names.size()) {
                Messages.DeleteConfig.sendToServer(names.remove(idx));
                clampScroll();
                rebuildRowButtons();
            }
        } else if (id == ID_SAVE) {
            String name = nameField.getText().trim();
            if (!name.isEmpty()) {
                if (!names.contains(name)) {
                    names.add(name);
                }
                Messages.SaveConfig.sendToServer(name);
                nameField.setText("");
                rebuildRowButtons();
            }
        } else if (id == ID_CLOSE) {
            mc.displayGuiScreen(null);
        }
    }

    // ── Input ──────────────────────────────────────────────────────────────

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) {
        if (mouseButton == 0 && handleScrollbarClick(mouseX, mouseY)) { return; }

        // Temporarily restrict row-button click detection to their visible viewport area.
        // Partially-scrolled buttons at the top/bottom edge must not respond to clicks
        // in the header or footer regions.
        int vpTop = py() + LIST_TOP, vpBot = py() + LIST_BOT;
        for (GuiButton b : buttonList) {
            int id = b.id;
            if ((id >= ID_LOAD && id < ID_LOAD + SLOT_COUNT) || (id >= ID_DEL && id < ID_DEL + SLOT_COUNT)) {
                // Only allow click if the center of the button is within the viewport
                b.visible = b.visible && (b.yPosition + 10 >= vpTop) && (b.yPosition + 10 < vpBot);
            }
        }
        super.mouseClicked(mouseX, mouseY, mouseButton);
        rebuildRowButtons(); // restore correct visibility

        nameField.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        if (isDraggingScrollbar) {
            setScrollFromThumbY(mouseY - scrollbarDragOffsetY, py());
            return;
        }
        super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);
    }

    @Override
    protected void mouseMovedOrUp(int mouseX, int mouseY, int which) {
        if (which != -1) {
            isDraggingScrollbar = false;
        }
        super.mouseMovedOrUp(mouseX, mouseY, which);
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0) {
            pixelScroll += (wheel > 0) ? -SCROLL_STEP : SCROLL_STEP;
            clampScroll();
            rebuildRowButtons();
        }
    }

    @Override
    protected void keyTyped(char ch, int keyCode) {
        if (nameField.textboxKeyTyped(ch, keyCode)) { return; }
        if (keyCode == 28 || keyCode == 156) { // Enter / numpad Enter
            actionPerformed(findButton(ID_SAVE));
        } else {
            super.keyTyped(ch, keyCode);
        }
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        nameField.updateCursorCounter();
    }

    // ── Rendering ──────────────────────────────────────────────────────────

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();

        int px = px(), py = py();

        // Panel border + background
        drawRect(px - 1, py - 1, px + PANEL_W + 1, py + PANEL_H + 1, 0xFF555555);
        drawRect(px, py, px + PANEL_W, py + PANEL_H, 0xFF1E1E1E);

        // Title + divider
        drawCenteredString(
            fontRendererObj,
            "§e" + StatCollector.translateToLocal("mm.gui.saved_configs.title"),
            px + PANEL_W / 2,
            py + 7,
            0xFFFFFF
        );
        drawRect(px + MARGIN, py + 17, px + PANEL_W - MARGIN, py + 18, 0xFF666666);

        // ── Scissored list viewport ───────────────────────────────────────
        ScaledResolution sr = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight);
        int sf = sr.getScaleFactor();
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(
            px * sf,
            mc.displayHeight - (py + LIST_BOT) * sf,
            (COL_SB - 1) * sf,
            viewportH() * sf
        );

        int offset = slotOffset();
        for (int slot = 0; slot < SLOT_COUNT; slot++) {
            int entryIdx = entryForSlot(slot);
            int ry = py + LIST_TOP + slot * ENTRY_H - offset;
            if (ry >= py + LIST_BOT) {
                break;
            }
            if (ry + ENTRY_H <= py + LIST_TOP) {
                continue;
            }

            if ((entryIdx % 2) == 0) {
                drawRect(px + 4, ry + 1, px + COL_LOAD - 4, ry + ENTRY_H - 1, 0x18FFFFFF);
            }

            if (entryIdx < names.size()) {
                String label = fontRendererObj.trimStringToWidth(names.get(entryIdx), COL_LOAD - 16);
                fontRendererObj.drawString(label, px + MARGIN, ry + 7, 0xFFFFFF);
            } else if (names.isEmpty() && entryIdx == 0) {
                fontRendererObj.drawString(
                    StatCollector.translateToLocal("mm.gui.saved_configs.empty"),
                    px + MARGIN,
                    ry + 7,
                    0xFF777777
                );
            }
        }

        // Row buttons drawn inside scissor so they clip at viewport edges
        for (GuiButton btn : buttonList) {
            int id = btn.id;
            if ((id >= ID_LOAD && id < ID_LOAD + SLOT_COUNT) || (id >= ID_DEL && id < ID_DEL + SLOT_COUNT)) {
                if (btn.visible) {
                    btn.drawButton(mc, mouseX, mouseY);
                }
            }
        }

        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        // ── End scissored area ────────────────────────────────────────────

        // Scrollbar
        drawScrollbar(mouseX, mouseY, px, py);

        // Footer divider
        drawRect(px + MARGIN, py + LIST_BOT + 2, px + PANEL_W - MARGIN, py + LIST_BOT + 3, 0xFF666666);

        // Config name label + field
        fontRendererObj.drawString(
            StatCollector.translateToLocal("mm.gui.saved_configs.name_label"),
            px + MARGIN,
            py + LIST_BOT + 6,
            0xAAAAAA
        );
        nameField.drawTextBox();

        // Static buttons (Save, Close) – drawn outside scissor
        for (GuiButton btn : buttonList) {
            if (btn.id == ID_SAVE || btn.id == ID_CLOSE) {
                btn.drawButton(mc, mouseX, mouseY);
            }
        }
    }

    private void drawScrollbar(int mouseX, int mouseY, int px, int py) {
        if (maxScroll() <= 0) { return; }
        int sbX = px + COL_SB;
        int trackTop = py + LIST_TOP;
        int trackBot = py + LIST_BOT - 2;

        // Track
        drawRect(sbX, trackTop, sbX + SB_W, trackBot, 0xFF2A2A2A);

        // Thumb
        int thumbH = sbThumbH();
        int thumbY = sbThumbY(py);
        boolean hover = isDraggingScrollbar || (mouseX >= sbX && mouseX < sbX + SB_W && mouseY >= thumbY && mouseY < thumbY + thumbH);
        drawRect(sbX + 1, thumbY, sbX + SB_W - 1, thumbY + thumbH, hover ? 0xFFAAAAAA : 0xFF888888);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
