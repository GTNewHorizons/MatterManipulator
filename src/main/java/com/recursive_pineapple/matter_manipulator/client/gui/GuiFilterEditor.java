package com.recursive_pineapple.matter_manipulator.client.gui;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.ScaledResolution;

import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

import com.recursive_pineapple.matter_manipulator.common.building.filter.FilterRuleParser;
import com.recursive_pineapple.matter_manipulator.common.networking.Messages;

/**
 * Visual builder UI for exchange mode filter rules.
 * Replaces the old chat-based filter input mechanism.
 */
@SideOnly(Side.CLIENT)
public class GuiFilterEditor extends GuiScreen {

    // Panel dimensions
    private static final int PW = 400;
    private static final int PH = 224;

    // Offsets from panel top (py)
    private static final int O_DIV1   = 17;   // first horizontal divider y
    private static final int O_ADDBTN = 20;   // add condition button y
    private static final int O_VPT    = 38;   // viewport top y (relative to panel top)
    private static final int VP_H     = 128;  // viewport height
    private static final int O_VPB    = O_VPT + VP_H; // = 166, viewport bottom y
    private static final int O_PREV   = O_VPB + 6;    // = 172, preview text y
    private static final int O_STAT   = O_PREV + 12;  // = 184, status text y
    private static final int O_BTNS   = O_STAT + 14;  // = 198, buttons y

    // Condition row dimensions
    private static final int RH = 18;  // condition row height
    private static final int CH = 12;  // connector (AND/OR) row height
    private static final int RG = 2;   // gap between row and connector

    // Button IDs
    private static final int ID_ADD    = 1000;
    private static final int ID_APPLY  = 1001;
    private static final int ID_CANCEL = 1002;
    // Per-row: ID_ROW + rowIndex * 10 + type  (type: 0=NOT, 1=POS, 2=IS, 3=REMOVE)
    private static final int ID_ROW  = 2000;
    // Connector: ID_CONN + rowIndex * 2 + type  (type: 0=AND, 1=OR)
    private static final int ID_CONN = 3000;

    private static final int GREEN = 0x55FF55;

    // All available positions in the filter DSL
    private static final String[] POSITIONS = {
        "self", "above", "below", "north", "south", "east", "west",
        "any NSEW", "all NSEW", "any NSEWUD", "all NSEWUD"
    };
    // Shorter display labels for the cycling button
    private static final String[] POS_LABELS = {
        "self", "above", "below", "north", "south", "east", "west",
        "any H", "all H", "any 6", "all 6"
    };

    private static class Condition {
        boolean not = false;
        int pos = 0;        // index into POSITIONS
        boolean negated = false;  // "is not" vs "is"
        String block = "";
        String conn = "and"; // connector leading into this row (for rows after the first)
    }

    private final List<Condition> conds = new ArrayList<>();
    private final List<GuiTextField> textFields = new ArrayList<>();
    private final List<Integer> fieldScreenY = new ArrayList<>();  // screen y of each text field

    private int scroll = 0;
    private String preview = "";
    private String status = "";
    private boolean valid = false;
    private GuiButton applyBtn;

    public GuiFilterEditor(String existingFilter) {
        // Start with one empty condition; ignore existingFilter since back-parsing is non-trivial
        conds.add(new Condition());
    }

    private int px() { return (width - PW) / 2; }
    private int py() { return (height - PH) / 2; }
    private int vpTop() { return py() + O_VPT; }
    private int vpBot() { return py() + O_VPB; }

    /** Virtual y (within the scroll list) of condition row at index i. */
    private int rowVY(int i) {
        return i * (RH + RG + CH + RG);
    }

    /** Total pixel height of all condition rows (including connectors between them). */
    private int listH() {
        int n = conds.size();
        return n == 0 ? 0 : (n - 1) * (RH + RG + CH + RG) + RH;
    }

    private void clampScroll() {
        int max = Math.max(0, listH() - VP_H);
        scroll = Math.max(0, Math.min(scroll, max));
    }

    /** Copy text field contents back into condition data. */
    private void syncFields() {
        for (int i = 0; i < textFields.size() && i < conds.size(); i++) {
            conds.get(i).block = textFields.get(i).getText().trim();
        }
    }

    @Override
    public void initGui() {
        super.initGui();
        rebuild();
    }

    @SuppressWarnings("unchecked")
    private void rebuild() {
        syncFields();
        buttonList.clear();
        textFields.clear();
        fieldScreenY.clear();

        int px = px(), py = py();
        int vpTop = vpTop(), vpBot = vpBot();

        clampScroll();

        // [+ Add Condition] button
        buttonList.add(new GuiButton(ID_ADD, px + 10, py + O_ADDBTN, 120, 14, "§a+ Add Condition"));

        // Build condition rows and their connectors
        for (int i = 0; i < conds.size(); i++) {
            Condition c = conds.get(i);

            int vy = rowVY(i);
            int sy = vpTop + vy - scroll;   // screen y of this row's top
            boolean rowVis = sy + RH > vpTop && sy < vpBot;

            // [NOT] toggle
            GuiButton notBtn = new GuiButton(ID_ROW + i * 10, px + 10, sy, 30, RH - 2,
                c.not ? "[NOT]" : " not ");
            if (c.not) notBtn.packedFGColour = GREEN;
            if (rowVis) buttonList.add(notBtn);

            // [Position ►] cycling button
            GuiButton posBtn = new GuiButton(ID_ROW + i * 10 + 1, px + 44, sy, 80, RH - 2,
                POS_LABELS[c.pos] + " ►");
            if (rowVis) buttonList.add(posBtn);

            // [is / is not] toggle
            GuiButton isBtn = new GuiButton(ID_ROW + i * 10 + 2, px + 128, sy, 50, RH - 2,
                c.negated ? "is not" : "is");
            if (rowVis) buttonList.add(isBtn);

            // Block name text field
            int fsy = sy + 2;
            GuiTextField field = new GuiTextField(fontRendererObj, px + 182, fsy, 152, RH - 4);
            field.setMaxStringLength(200);
            field.setText(c.block);
            textFields.add(field);
            fieldScreenY.add(fsy);

            // [X] remove button
            GuiButton removeBtn = new GuiButton(ID_ROW + i * 10 + 3, px + 338, sy, 18, RH - 2, "X");
            if (rowVis) buttonList.add(removeBtn);

            // Connector [AND] / [OR] between this row and the next
            if (i < conds.size() - 1) {
                Condition next = conds.get(i + 1);
                int cvy = vy + RH + RG;
                int csy = vpTop + cvy - scroll;
                boolean connVis = csy + CH > vpTop && csy < vpBot;

                boolean isAnd = "and".equals(next.conn);
                GuiButton andBtn = new GuiButton(ID_CONN + i * 2, px + 155, csy, 42, CH,
                    isAnd ? "[AND]" : " AND ");
                GuiButton orBtn  = new GuiButton(ID_CONN + i * 2 + 1, px + 201, csy, 42, CH,
                    isAnd ? "  OR  " : "[ OR ]");
                andBtn.packedFGColour = isAnd ? GREEN : 0;
                orBtn.packedFGColour  = isAnd ? 0 : GREEN;

                if (connVis) {
                    buttonList.add(andBtn);
                    buttonList.add(orBtn);
                }
            }
        }

        // Footer: Apply and Cancel
        applyBtn = new GuiButton(ID_APPLY, px + 10, py + O_BTNS, 90, 16, "Apply");
        buttonList.add(applyBtn);
        buttonList.add(new GuiButton(ID_CANCEL, px + PW - 100, py + O_BTNS, 90, 16, "Cancel"));

        updatePreview();
    }

    private void updatePreview() {
        syncFields();
        StringBuilder sb = new StringBuilder();
        boolean anyEmpty = false;

        for (int i = 0; i < conds.size(); i++) {
            Condition c = conds.get(i);
            if (c.block.isEmpty()) anyEmpty = true;
            if (i > 0) sb.append(" ").append(c.conn).append(" ");
            if (c.not) sb.append("not (");
            sb.append(POSITIONS[c.pos]).append(" is");
            if (c.negated) sb.append(" not");
            sb.append(" ").append(c.block.isEmpty() ? "<block>" : c.block);
            if (c.not) sb.append(")");
        }

        preview = sb.toString();

        if (anyEmpty) {
            status = "§eFill in all block names";
            valid = false;
        } else {
            try {
                FilterRuleParser.parse(preview);
                status = "§aValid filter";
                valid = true;
            } catch (FilterRuleParser.ParseException e) {
                String msg = e.getMessage();
                status = "§c" + (msg.length() > 55 ? msg.substring(0, 52) + "..." : msg);
                valid = false;
            }
        }

        if (applyBtn != null) applyBtn.enabled = valid;
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        int id = button.id;

        if (id == ID_ADD) {
            conds.add(new Condition());
            rebuild();
        } else if (id == ID_APPLY && valid) {
            Messages.SetFilterRule.sendToServer(preview);
            mc.displayGuiScreen(null);
        } else if (id == ID_CANCEL) {
            mc.displayGuiScreen(null);
        } else if (id >= ID_ROW && id < ID_CONN) {
            int i = (id - ID_ROW) / 10;
            int t = (id - ID_ROW) % 10;
            if (i >= 0 && i < conds.size()) {
                Condition c = conds.get(i);
                switch (t) {
                    case 0 -> { c.not = !c.not; rebuild(); }
                    case 1 -> { c.pos = (c.pos + 1) % POSITIONS.length; rebuild(); }
                    case 2 -> { c.negated = !c.negated; rebuild(); }
                    case 3 -> {
                        syncFields();
                        conds.remove(i);
                        if (conds.isEmpty()) conds.add(new Condition());
                        rebuild();
                    }
                }
            }
        } else if (id >= ID_CONN) {
            int rel   = id - ID_CONN;
            int nextI = rel / 2 + 1;
            int type  = rel % 2;
            if (nextI < conds.size()) {
                conds.get(nextI).conn = (type == 0) ? "and" : "or";
                rebuild();
            }
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();

        int px = px(), py = py();
        int vpTop = vpTop(), vpBot = vpBot();

        // Panel background
        drawRect(px - 1, py - 1, px + PW + 1, py + PH + 1, 0xFF555555);
        drawRect(px, py, px + PW, py + PH, 0xFF1E1E1E);

        // Title
        drawCenteredString(fontRendererObj, "§eFilter Editor", px + PW / 2, py + 6, 0xFFFFFF);
        drawRect(px + 8, py + O_DIV1, px + PW - 8, py + O_DIV1 + 1, 0xFF666666);

        // Scroll hint
        if (listH() > VP_H) {
            int used = Math.min(scroll + VP_H, listH());
            String hint = String.format("§7[scroll %d/%d]", scroll, Math.max(0, listH() - VP_H));
            drawString(fontRendererObj, hint, px + 140, py + O_ADDBTN + 1, 0xFFFFFF);
        }

        // Dividers around conditions viewport
        drawRect(px + 8, vpTop - 1, px + PW - 8, vpTop,     0xFF666666);
        drawRect(px + 8, vpBot,     px + PW - 8, vpBot + 1, 0xFF666666);

        // Draw static buttons (Add, Apply, Cancel) — no clipping needed
        for (int bi = 0; bi < buttonList.size(); bi++) {
            GuiButton btn = (GuiButton) buttonList.get(bi);
            if (btn.id == ID_ADD || btn.id == ID_APPLY || btn.id == ID_CANCEL) {
                btn.drawButton(mc, mouseX, mouseY);
            }
        }

        // Clip to conditions viewport for row/connector buttons and text fields
        ScaledResolution sr = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight);
        int sf = sr.getScaleFactor();
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(
            (px + 8) * sf,
            mc.displayHeight - vpBot * sf,
            (PW - 16) * sf,
            VP_H * sf
        );

        for (int bi = 0; bi < buttonList.size(); bi++) {
            GuiButton btn = (GuiButton) buttonList.get(bi);
            if (btn.id != ID_ADD && btn.id != ID_APPLY && btn.id != ID_CANCEL) {
                btn.drawButton(mc, mouseX, mouseY);
            }
        }

        for (int i = 0; i < textFields.size(); i++) {
            int fy = fieldScreenY.get(i);
            if (fy + RH > vpTop && fy < vpBot) {
                textFields.get(i).drawTextBox();
            }
        }

        GL11.glDisable(GL11.GL_SCISSOR_TEST);

        // Preview
        int prevY = py + O_PREV;
        String dispPrev = preview.length() > 58 ? preview.substring(0, 55) + "..." : preview;
        drawString(fontRendererObj, "§7▷ " + dispPrev, px + 10, prevY, 0xFFFFFF);
        drawString(fontRendererObj, status, px + 10, prevY + 12, 0xFFFFFF);
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        super.keyTyped(typedChar, keyCode);
        for (GuiTextField tf : textFields) {
            tf.textboxKeyTyped(typedChar, keyCode);
        }
        updatePreview();
    }

    @Override
    protected void mouseClicked(int x, int y, int button) {
        super.mouseClicked(x, y, button);
        int vpTop = vpTop(), vpBot = vpBot();
        for (int i = 0; i < textFields.size(); i++) {
            int fy = fieldScreenY.get(i);
            if (fy + RH > vpTop && fy < vpBot) {
                textFields.get(i).mouseClicked(x, y, button);
            }
        }
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        for (GuiTextField tf : textFields) {
            tf.updateCursorCounter();
        }
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        int dw = Mouse.getEventDWheel();
        if (dw != 0) {
            scroll -= Integer.signum(dw) * (RH + RG + CH + RG);
            clampScroll();
            rebuild();
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
