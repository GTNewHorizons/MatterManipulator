package com.recursive_pineapple.matter_manipulator.client.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.util.StatCollector;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

import com.recursive_pineapple.matter_manipulator.common.items.manipulator.MMConfig;
import com.recursive_pineapple.matter_manipulator.common.networking.Messages;

import org.lwjgl.input.Mouse;

@SideOnly(Side.CLIENT)
public class GuiSavedConfigs extends GuiScreen {

    // ── Panel ──────────────────────────────────────────────────────────────
    private static final int PANEL_W = 300;
    private static final int PANEL_H = 222;
    private static final int MARGIN = 8;

    // ── List ───────────────────────────────────────────────────────────────
    private static final int LIST_TOP = 22;
    private static final int ENTRY_H = 22; // 20px button + 1px padding top/bottom
    private static final int MAX_VIS = 6;
    private static final int LIST_BOT = LIST_TOP + MAX_VIS * ENTRY_H; // 154

    // ── Horizontal columns (panel-relative) ───────────────────────────────
    // text: 4..191 load: 196..239 delete: 244..287 scrollbar: 292..296
    private static final int COL_LOAD = 196;
    private static final int COL_DEL = 244;
    private static final int COL_SB = 292;
    private static final int SB_W = 5;

    // ── Button IDs ─────────────────────────────────────────────────────────
    private static final int ID_LOAD = 100; // 100..105
    private static final int ID_DEL = 200; // 200..205
    private static final int ID_SAVE = 300;
    private static final int ID_CLOSE = 301;

    // ── State ──────────────────────────────────────────────────────────────
    private final List<String> names = new ArrayList<>();
    private int scroll = 0;
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

    // ── Init ───────────────────────────────────────────────────────────────

    @Override
    public void initGui() {
        buttonList.clear();
        int px = px(), py = py();

        for (int i = 0; i < MAX_VIS; i++) {
            int ry = py + LIST_TOP + i * ENTRY_H;
            buttonList.add(
                new GuiButton(
                    ID_LOAD + i,
                    px + COL_LOAD,
                    ry + 1,
                    44,
                    20,
                    StatCollector.translateToLocal("mm.gui.saved_configs.load")
                )
            );
            buttonList.add(
                new GuiButton(
                    ID_DEL + i,
                    px + COL_DEL,
                    ry + 1,
                    44,
                    20,
                    StatCollector.translateToLocal("mm.gui.saved_configs.delete")
                )
            );
        }

        // Save & Close – same Y, equal margin to sides and bottom
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

        syncButtons();
    }

    private void syncButtons() {
        for (int i = 0; i < MAX_VIS; i++) {
            boolean exists = (i + scroll) < names.size();
            findButton(ID_LOAD + i).enabled = exists;
            findButton(ID_DEL + i).enabled = exists;
        }
    }

    private GuiButton findButton(int id) {
        for (GuiButton b : buttonList) {
            if (b.id == id) { return b; }
        }
        throw new IllegalStateException("Button " + id + " not found");
    }

    // ── Scrollbar helpers ──────────────────────────────────────────────────

    /**
     * Pixel height of the scrollbar track.
     */
    private int sbTrackH() {
        return LIST_BOT - 2 - LIST_TOP;
    } // 4px gap from each divider

    /**
     * Pixel height of the scrollbar thumb.
     */
    private int sbThumbH() {
        if (names.size() <= MAX_VIS) { return sbTrackH(); }
        return Math.max(10, sbTrackH() * MAX_VIS / names.size());
    }

    /**
     * Absolute Y of the scrollbar thumb top.
     */
    private int sbThumbY(int py) {
        if (names.size() <= MAX_VIS) { return py + LIST_TOP; }
        int travel = sbTrackH() - sbThumbH();
        if (travel <= 0) { return py + LIST_TOP; }
        return py + LIST_TOP + scroll * travel / (names.size() - MAX_VIS);
    }

    /**
     * Update scroll so the thumb top is at the given absolute Y.
     */
    private void setScrollFromThumbY(int thumbAbsY, int py) {
        int travel = sbTrackH() - sbThumbH();
        if (travel <= 0) {
            scroll = 0;
            return;
        }
        scroll = (thumbAbsY - (py + LIST_TOP)) * (names.size() - MAX_VIS) / travel;
        scroll = Math.max(0, Math.min(scroll, names.size() - MAX_VIS));
        syncButtons();
    }

    private boolean handleScrollbarClick(int mouseX, int mouseY) {
        if (names.size() <= MAX_VIS) { return false; }
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

        if (id >= ID_LOAD && id < ID_LOAD + MAX_VIS) {
            int idx = id - ID_LOAD + scroll;
            if (idx < names.size()) {
                Messages.LoadConfig.sendToServer(names.get(idx));
                mc.displayGuiScreen(null);
            }
        } else if (id >= ID_DEL && id < ID_DEL + MAX_VIS) {
            int idx = id - ID_DEL + scroll;
            if (idx < names.size()) {
                Messages.DeleteConfig.sendToServer(names.remove(idx));
                scroll = Math.min(scroll, Math.max(0, names.size() - MAX_VIS));
                syncButtons();
            }
        } else if (id == ID_SAVE) {
            String name = nameField.getText().trim();
            if (!name.isEmpty()) {
                if (!names.contains(name)) {
                    names.add(name);
                }
                Messages.SaveConfig.sendToServer(name);
                nameField.setText("");
                syncButtons();
            }
        } else if (id == ID_CLOSE) {
            mc.displayGuiScreen(null);
        }
    }

    // ── Input ──────────────────────────────────────────────────────────────

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) {
        if (mouseButton == 0 && handleScrollbarClick(mouseX, mouseY)) { return; }
        super.mouseClicked(mouseX, mouseY, mouseButton);
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
            scroll += (wheel > 0) ? -1 : 1;
            scroll = Math.max(0, Math.min(scroll, Math.max(0, names.size() - MAX_VIS)));
            syncButtons();
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

    // ── Rendering ──────────────────────────────────────────────────────────

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();

        int px = px(), py = py();

        // Panel border + background
        drawRect(px - 1, py - 1, px + PANEL_W + 1, py + PANEL_H + 1, 0xFF555555);
        drawRect(px, py, px + PANEL_W, py + PANEL_H, 0xFF1E1E1E);

        // Title
        drawCenteredString(
            fontRendererObj,
            "§e" + StatCollector.translateToLocal("mm.gui.saved_configs.title"),
            px + PANEL_W / 2,
            py + 7,
            0xFFFFFF
        );
        drawRect(px + MARGIN, py + 17, px + PANEL_W - MARGIN, py + 18, 0xFF666666);

        // List rows
        for (int i = 0; i < MAX_VIS; i++) {
            int idx = i + scroll;
            int ry = py + LIST_TOP + i * ENTRY_H;

            if ((i % 2) == 0) {
                drawRect(px + 4, ry + 1, px + COL_LOAD - 4, ry + ENTRY_H - 1, 0x18FFFFFF);
            }

            if (idx < names.size()) {
                String label = fontRendererObj.trimStringToWidth(names.get(idx), COL_LOAD - 16);
                fontRendererObj.drawString(label, px + MARGIN, ry + 7, 0xFFFFFF);
            } else if (names.isEmpty() && i == 0) {
                fontRendererObj.drawString(
                    StatCollector.translateToLocal("mm.gui.saved_configs.empty"),
                    px + MARGIN,
                    ry + 7,
                    0xFF777777
                );
            }
        }

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

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private void drawScrollbar(int mouseX, int mouseY, int px, int py) {
        if (names.size() <= MAX_VIS) { return; }
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
