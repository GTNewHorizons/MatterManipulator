package com.recursive_pineapple.matter_manipulator.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * Self-contained position-picker dropdown overlay drawn over the condition rows.
 * All state, layout constants, rendering, and click handling live here.
 */
@SideOnly(Side.CLIENT)
class FilterPickerOverlay {

    // ── Layout constants ───────────────────────────────────────────────────
    static final String[] DIR_LABELS = {
        "north", "south", "west", "east", "above", "below", "self", "at X Y Z"
    };
    static final int[] DIR_BITS = {
        FilterExprTree.DIR_NORTH,
        FilterExprTree.DIR_SOUTH,
        FilterExprTree.DIR_WEST,
        FilterExprTree.DIR_EAST,
        FilterExprTree.DIR_ABOVE,
        FilterExprTree.DIR_BELOW,
        FilterExprTree.DIR_SELF,
        0 // 0 = posAt mode
    };
    static final int AT_IDX = 7;
    static final int BTN_W = 80, BTN_H = 20, GAP = 2;
    static final int COLS = 2;
    static final int DIR_ROWS = (DIR_LABELS.length + COLS - 1) / COLS; // 4
    static final int SEPARATOR_H = 7;
    static final int W = COLS * (BTN_W + GAP) - GAP + 8;
    static final int H = 8 + DIR_ROWS * (BTN_H + GAP) + SEPARATOR_H + BTN_H;

    // ── State ──────────────────────────────────────────────────────────────
    private int linkedItem = -1;
    private int screenX, screenY;

    // ── Open / close ───────────────────────────────────────────────────────

    void open(int itemIdx, int x, int y) {
        linkedItem = itemIdx;
        screenX = x;
        screenY = y;
    }

    void close() {
        linkedItem = -1;
    }

    boolean isOpen() {
        return linkedItem >= 0;
    }

    int linkedItem() {
        return linkedItem;
    }

    // ── Position helpers ───────────────────────────────────────────────────

    int clampedX(int screenW) {
        return Math.min(screenX, screenW - W - 2);
    }

    int clampedY(int screenH) {
        return Math.min(screenY, screenH - H - 2);
    }

    boolean isMouseOver(int mouseX, int mouseY, int screenW, int screenH) {
        if (!isOpen()) { return false; }
        int px = clampedX(screenW), py = clampedY(screenH);
        return mouseX >= px && mouseX < px + W && mouseY >= py && mouseY < py + H;
    }

    // ── Rendering ──────────────────────────────────────────────────────────

    void draw(Minecraft mc, int mouseX, int mouseY, int screenW, int screenH, CondNode c, int colorActive) {
        int px = clampedX(screenW), py = clampedY(screenH);

        GuiScreen.drawRect(px - 1, py - 1, px + W + 1, py + H + 1, 0xFFAAAAAA);
        GuiScreen.drawRect(px, py, px + W, py + H, 0xFF333333);

        for (int i = 0; i < DIR_LABELS.length; i++) {
            int bx = px + 4 + (i % COLS) * (BTN_W + GAP);
            int by = py + 4 + (i / COLS) * (BTN_H + GAP);
            GuiButton btn = new GuiButton(i, bx, by, BTN_W, BTN_H, DIR_LABELS[i]);
            if (c != null) {
                boolean active = (i == AT_IDX) ? c.posAt : (!c.posAt && (c.posMask & DIR_BITS[i]) != 0);
                if (active) {
                    btn.packedFGColour = colorActive;
                }
            }
            btn.drawButton(mc, mouseX, mouseY);
        }

        int dirGridBottom = py + 4 + DIR_ROWS * (BTN_H + GAP) - GAP;
        GuiScreen.drawRect(px + 3, dirGridBottom + SEPARATOR_H / 2, px + W - 3, dirGridBottom + SEPARATOR_H / 2 + 1, 0xFF555555);

        boolean multiSelect = c != null && !c.posAt && Integer.bitCount(c.posMask) >= 2;
        int anyAllY = dirGridBottom + SEPARATOR_H;
        GuiButton anyBtn = new GuiButton(DIR_LABELS.length, px + 4, anyAllY, BTN_W, BTN_H, "any");
        GuiButton allBtn = new GuiButton(DIR_LABELS.length + 1, px + 4 + BTN_W + GAP, anyAllY, BTN_W, BTN_H, "all");
        anyBtn.enabled = multiSelect;
        allBtn.enabled = multiSelect;
        if (multiSelect && c.posAny) {
            anyBtn.packedFGColour = colorActive;
        }
        if (multiSelect && !c.posAny) {
            allBtn.packedFGColour = colorActive;
        }
        anyBtn.drawButton(mc, mouseX, mouseY);
        allBtn.drawButton(mc, mouseX, mouseY);
    }

    // ── Click handling ─────────────────────────────────────────────────────

    /**
     * Handles a mouse click while the picker is open.
     * Modifies {@code c} in-place for direction/any-all toggles.
     *
     * @return true if the click was consumed (caller should rebuild if true)
     */
    boolean handleClick(int mouseX, int mouseY, int screenW, int screenH, CondNode c) {
        int px = clampedX(screenW), py = clampedY(screenH);

        for (int i = 0; i < DIR_LABELS.length; i++) {
            int bx = px + 4 + (i % COLS) * (BTN_W + GAP);
            int by = py + 4 + (i / COLS) * (BTN_H + GAP);
            if (mouseX >= bx && mouseX < bx + BTN_W && mouseY >= by && mouseY < by + BTN_H) {
                if (c != null) {
                    if (i == AT_IDX) {
                        c.posAt = !c.posAt;
                        if (!c.posAt && c.posMask == 0) {
                            c.posMask = FilterExprTree.DIR_NORTH;
                        }
                    } else {
                        int bit = DIR_BITS[i];
                        if (c.posAt) {
                            c.posAt = false;
                            c.posMask = bit;
                        } else if ((c.posMask & bit) != 0 && Integer.bitCount(c.posMask) == 1) {
                            // Don't deselect the last remaining direction
                        } else {
                            c.posMask ^= bit;
                        }
                    }
                }
                return true;
            }
        }

        boolean multiSelect = c != null && !c.posAt && Integer.bitCount(c.posMask) >= 2;
        if (multiSelect) {
            int dirGridBottom = py + 4 + DIR_ROWS * (BTN_H + GAP) - GAP;
            int anyAllY = dirGridBottom + SEPARATOR_H;
            if (mouseY >= anyAllY && mouseY < anyAllY + BTN_H) {
                if (mouseX >= px + 4 && mouseX < px + 4 + BTN_W) {
                    c.posAny = true;
                    return true;
                }
                if (mouseX >= px + 4 + BTN_W + GAP && mouseX < px + 4 + 2 * BTN_W + GAP) {
                    c.posAny = false;
                    return true;
                }
            }
        }

        return isMouseOver(mouseX, mouseY, screenW, screenH);
    }
}
