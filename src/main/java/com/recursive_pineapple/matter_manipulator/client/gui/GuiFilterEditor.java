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
 * Visual filter rule builder for exchange mode.
 * Supports nested groups, at-position, and a popup position picker.
 */
@SideOnly(Side.CLIENT)
public class GuiFilterEditor extends GuiScreen {

    // ── Panel ──────────────────────────────────────────────────────────────
    private static final int PW = 420, PH = 252;
    private static final int O_VPT = 38;   // viewport top offset from panel top
    private static final int VP_H  = 140;  // viewport height
    private static final int O_VPB = O_VPT + VP_H;
    private static final int O_PRV = O_VPB + 5;   // preview text y
    private static final int O_STS = O_PRV + 12;  // status text y
    private static final int O_BTN = O_STS + 14;  // footer buttons y

    // ── Row sizes ──────────────────────────────────────────────────────────
    private static final int RH  = 18; // condition row height
    private static final int GH  = 14; // group-open header height
    private static final int GCH = 10; // group-close footer height
    private static final int CNH = 12; // AND/OR connector height
    private static final int GAP =  2; // gap after each render item
    private static final int IND = 10; // horizontal indent per depth level

    // ── Positions ─────────────────────────────────────────────────────────
    // Index 11 is the special "at X Y Z" mode.
    private static final int POS_AT = 11;
    private static final String[] POSITIONS = {
        "self", "above", "below", "north", "south", "east", "west",
        "any NSEW", "all NSEW", "any NSEWUD", "all NSEWUD"
    };
    private static final String[] PICKER_LABELS = {
        "self", "above", "below", "north", "south", "east", "west",
        "any NSEW", "all NSEW", "any NSEWUD", "all NSEWUD", "at X Y Z"
    };

    // ── Button IDs ────────────────────────────────────────────────────────
    // Static
    private static final int ID_ROOT_COND  = 800;
    private static final int ID_ROOT_GROUP = 801;
    private static final int ID_APPLY      = 802;
    private static final int ID_CANCEL     = 803;
    // Per render-item: ID_ITEM_BASE + ri * 20 + sub-type
    //   COND:       0=NOT  1=POS  2=IS  3=REMOVE
    //   GROUP_OPEN: 0=NOT  3=REMOVE  4=ADD_COND  5=ADD_GROUP
    //   CONN:       0=AND  1=OR
    private static final int ID_ITEM_BASE  = 1000;
    // Position picker buttons (drawn manually, not in buttonList)
    private static final int ID_PICKER_BASE = 60000;

    private static final int GREEN = 0x55FF55;

    // ── Data model ────────────────────────────────────────────────────────

    abstract static class ExprNode {
        boolean not = false;
        String  conn = "and";   // connector leading TO this node from its predecessor
        GroupNode parent;
    }

    static class CondNode extends ExprNode {
        int posIdx = 0;         // 0..10 = named position; 11 = "at X Y Z"
        int atX = 0, atY = 0, atZ = 0;
        boolean negated = false;
        String block = "";
    }

    static class GroupNode extends ExprNode {
        final List<ExprNode> children = new ArrayList<>();
    }

    // ── Render list ───────────────────────────────────────────────────────

    enum RItemType { COND, GROUP_OPEN, GROUP_CLOSE, CONN }

    static class RItem {
        final RItemType type;
        final ExprNode  node;   // for CONN: the node that comes after the connector
        final int       depth;
        int vy, height;         // filled by flattenGroup

        RItem(RItemType t, ExprNode n, int d) { type = t; node = n; depth = d; }
    }

    // ── State ─────────────────────────────────────────────────────────────

    private final GroupNode root = new GroupNode();
    private final List<RItem> rItems = new ArrayList<>();

    // Parallel to rItems — only COND slots are non-null
    private final List<GuiTextField>   blockFields = new ArrayList<>();
    private final List<GuiTextField[]> atFields    = new ArrayList<>(); // [dx,dy,dz] or null
    private final List<Integer>        fieldSY     = new ArrayList<>(); // screen-y of block field

    private int scroll = 0, listH = 0;
    private String preview = "", status = "";
    private boolean valid = false;
    private GuiButton applyBtn;

    // Position picker
    private int pickerForRI = -1;   // rItem index whose picker is open; -1 = closed
    private int pickerSX, pickerSY; // screen position of picker top-left

    // ── Constructor ───────────────────────────────────────────────────────

    public GuiFilterEditor(String existingFilter) {
        CondNode first = new CondNode();
        first.parent = root;
        root.children.add(first);
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private int px()    { return (width - PW) / 2; }
    private int py()    { return (height - PH) / 2; }
    private int vpTop() { return py() + O_VPT; }
    private int vpBot() { return py() + O_VPB; }

    private void clampScroll() {
        scroll = Math.max(0, Math.min(scroll, Math.max(0, listH - VP_H)));
    }

    // ── Tree → render list ────────────────────────────────────────────────

    private void buildRenderList() {
        rItems.clear();
        int[] vy = { 0 };
        flattenGroup(root.children, 0, vy);
        listH = vy[0];
    }

    private void flattenGroup(List<ExprNode> children, int depth, int[] vy) {
        for (int i = 0; i < children.size(); i++) {
            ExprNode child = children.get(i);

            // Connector before this child (skipped for the first child)
            if (i > 0) {
                RItem conn = new RItem(RItemType.CONN, child, depth);
                conn.vy = vy[0]; conn.height = CNH + GAP;
                rItems.add(conn);
                vy[0] += CNH + GAP;
            }

            if (child instanceof CondNode) {
                RItem ri = new RItem(RItemType.COND, child, depth);
                ri.vy = vy[0]; ri.height = RH + GAP;
                rItems.add(ri);
                vy[0] += RH + GAP;
            } else if (child instanceof GroupNode) {
                GroupNode grp = (GroupNode) child;
                RItem open = new RItem(RItemType.GROUP_OPEN, grp, depth);
                open.vy = vy[0]; open.height = GH + GAP;
                rItems.add(open);
                vy[0] += GH + GAP;

                flattenGroup(grp.children, depth + 1, vy);

                RItem close = new RItem(RItemType.GROUP_CLOSE, grp, depth);
                close.vy = vy[0]; close.height = GCH + GAP;
                rItems.add(close);
                vy[0] += GCH + GAP;
            }
        }
    }

    // ── Filter string builder ─────────────────────────────────────────────

    private void buildGroupString(List<ExprNode> children, StringBuilder sb) {
        for (int i = 0; i < children.size(); i++) {
            ExprNode child = children.get(i);
            if (i > 0) sb.append(" ").append(child.conn).append(" ");

            if (child instanceof CondNode) {
                CondNode c = (CondNode) child;
                if (c.not) sb.append("not (");
                if (c.posIdx == POS_AT) {
                    sb.append("at ").append(c.atX).append(" ").append(c.atY).append(" ").append(c.atZ);
                } else {
                    sb.append(POSITIONS[c.posIdx]);
                }
                sb.append(" is");
                if (c.negated) sb.append(" not");
                sb.append(" ").append(c.block.isEmpty() ? "<block>" : c.block);
                if (c.not) sb.append(")");
            } else if (child instanceof GroupNode) {
                GroupNode g = (GroupNode) child;
                if (child.not) sb.append("not ");
                sb.append("(");
                buildGroupString(g.children, sb);
                sb.append(")");
            }
        }
    }

    private boolean hasEmptyBlock(List<ExprNode> children) {
        for (ExprNode n : children) {
            if (n instanceof CondNode && ((CondNode) n).block.isEmpty()) return true;
            if (n instanceof GroupNode && hasEmptyBlock(((GroupNode) n).children)) return true;
        }
        return false;
    }

    // ── Sync text fields → node data ──────────────────────────────────────

    private void syncFields() {
        for (int ri = 0; ri < rItems.size() && ri < blockFields.size(); ri++) {
            GuiTextField bf = blockFields.get(ri);
            if (bf == null) continue;
            RItem item = rItems.get(ri);
            if (item.type != RItemType.COND) continue;
            CondNode c = (CondNode) item.node;
            c.block = bf.getText().trim();
            GuiTextField[] af = atFields.get(ri);
            if (af != null) {
                c.atX = parseIntSafe(af[0].getText());
                c.atY = parseIntSafe(af[1].getText());
                c.atZ = parseIntSafe(af[2].getText());
            }
        }
    }

    private static int parseIntSafe(String s) {
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return 0; }
    }

    // ── GUI rebuild ───────────────────────────────────────────────────────

    @Override
    public void initGui() {
        super.initGui();
        rebuild();
    }

    @SuppressWarnings("unchecked")
    private void rebuild() {
        syncFields();
        buildRenderList();
        clampScroll();

        buttonList.clear();
        blockFields.clear();
        atFields.clear();
        fieldSY.clear();

        int px = px(), py = py();
        int vpTop = vpTop(), vpBot = vpBot();

        // ── Static buttons ──
        buttonList.add(new GuiButton(ID_ROOT_COND,  px + 10, py + 22, 95, 14, "§a+ Condition"));
        buttonList.add(new GuiButton(ID_ROOT_GROUP, px + 109, py + 22, 80, 14, "§9+ Group"));
        applyBtn = new GuiButton(ID_APPLY,  px + 10,       py + O_BTN, 90, 16, "Apply");
        buttonList.add(applyBtn);
        buttonList.add(new GuiButton(ID_CANCEL, px + PW - 100, py + O_BTN, 90, 16, "Cancel"));

        // ── Per-item buttons ──
        for (int ri = 0; ri < rItems.size(); ri++) {
            RItem item = rItems.get(ri);
            int sy  = vpTop + item.vy - scroll;
            int xi  = px + 10 + item.depth * IND;
            boolean vis = sy + item.height > vpTop && sy < vpBot;

            // reserve parallel slots (filled for COND only)
            blockFields.add(null);
            atFields.add(null);
            fieldSY.add(0);

            int base = ID_ITEM_BASE + ri * 20;

            switch (item.type) {
                case COND: {
                    CondNode c = (CondNode) item.node;
                    boolean isAt = c.posIdx == POS_AT;

                    // [NOT]
                    GuiButton nb = new GuiButton(base, xi, sy, 28, RH - 2, c.not ? "[NOT]" : " not ");
                    if (c.not) nb.packedFGColour = GREEN;
                    if (vis) buttonList.add(nb);
                    xi += 32;

                    if (isAt) {
                        // [at] label — click to switch back to named position
                        GuiButton atBtn = new GuiButton(base + 1, xi, sy, 22, RH - 2, "at");
                        if (vis) buttonList.add(atBtn);
                        xi += 26;

                        // dx / dy / dz text fields
                        GuiTextField dx = makeIntField(xi,       sy, 26, c.atX);
                        GuiTextField dy = makeIntField(xi + 29,  sy, 26, c.atY);
                        GuiTextField dz = makeIntField(xi + 58,  sy, 26, c.atZ);
                        atFields.set(ri, new GuiTextField[]{ dx, dy, dz });
                        xi += 90;
                    } else {
                        // [Position ▼] — click to open picker
                        GuiButton pb = new GuiButton(base + 1, xi, sy, 78, RH - 2,
                            PICKER_LABELS[c.posIdx] + " ▼");
                        if (vis) buttonList.add(pb);
                        xi += 82;
                    }

                    // [is / is not]
                    GuiButton ib = new GuiButton(base + 2, xi, sy, 48, RH - 2, c.negated ? "is not" : "is");
                    if (vis) buttonList.add(ib);
                    xi += 52;

                    // block name field (fills remaining width)
                    int rightEdge = px + PW - 10 - 4 - 16; // leave room for X button
                    int fw = rightEdge - xi;
                    int fsy = sy + 2;
                    GuiTextField bf = new GuiTextField(fontRendererObj, xi, fsy, fw, RH - 4);
                    bf.setMaxStringLength(200);
                    bf.setText(c.block);
                    blockFields.set(ri, bf);
                    fieldSY.set(ri, fsy);
                    xi = rightEdge + 4;

                    // [X] remove
                    GuiButton rb = new GuiButton(base + 3, xi, sy, 16, RH - 2, "X");
                    if (vis) buttonList.add(rb);
                    break;
                }

                case GROUP_OPEN: {
                    GroupNode g = (GroupNode) item.node;

                    // [NOT grp]
                    GuiButton nb = new GuiButton(base, xi, sy, 28, GH - 2, g.not ? "[NOT]" : " not ");
                    if (g.not) nb.packedFGColour = GREEN;
                    if (vis) buttonList.add(nb);
                    xi += 32;

                    // [+ Cond inside group]
                    GuiButton ac = new GuiButton(base + 4, xi, sy, 70, GH - 2, "§a+Cond");
                    if (vis) buttonList.add(ac);
                    xi += 74;

                    // [+ Group inside group]
                    GuiButton ag = new GuiButton(base + 5, xi, sy, 60, GH - 2, "§9+Group");
                    if (vis) buttonList.add(ag);

                    // [X] remove this group (right side)
                    int rx = px + PW - 10 - 16;
                    GuiButton rb = new GuiButton(base + 3, rx, sy, 16, GH - 2, "X");
                    if (vis) buttonList.add(rb);
                    break;
                }

                case GROUP_CLOSE:
                    // Visual only — no buttons
                    break;

                case CONN: {
                    // AND / OR centred in the panel
                    int cx = px + PW / 2 - 46;
                    boolean isAnd = "and".equals(item.node.conn);
                    GuiButton ab = new GuiButton(base,     cx,      sy, 44, CNH - 2, isAnd ? "[AND]" : " AND ");
                    GuiButton ob = new GuiButton(base + 1, cx + 48, sy, 44, CNH - 2, isAnd ? "  OR  " : "[ OR ]");
                    ab.packedFGColour = isAnd ? GREEN : 0;
                    ob.packedFGColour = isAnd ? 0 : GREEN;
                    if (vis) { buttonList.add(ab); buttonList.add(ob); }
                    break;
                }
            }
        }

        updatePreview();
    }

    private GuiTextField makeIntField(int x, int sy, int w, int initial) {
        GuiTextField f = new GuiTextField(fontRendererObj, x, sy + 2, w, RH - 4);
        f.setMaxStringLength(5);
        f.setText(String.valueOf(initial));
        return f;
    }

    private void updatePreview() {
        syncFields();
        StringBuilder sb = new StringBuilder();
        buildGroupString(root.children, sb);
        preview = sb.toString();

        if (hasEmptyBlock(root.children)) {
            status = "§eFill in all block names";
            valid = false;
        } else {
            try {
                FilterRuleParser.parse(preview);
                status = "§aValid filter";
                valid = true;
            } catch (FilterRuleParser.ParseException e) {
                String m = e.getMessage();
                status = "§c" + (m.length() > 58 ? m.substring(0, 55) + "..." : m);
                valid = false;
            }
        }
        if (applyBtn != null) applyBtn.enabled = valid;
    }

    // ── Actions ───────────────────────────────────────────────────────────

    @Override
    protected void actionPerformed(GuiButton button) {
        int id = button.id;

        // Close picker unless we clicked another pos button (handled below)
        if (pickerForRI >= 0 && id != ID_ITEM_BASE + pickerForRI * 20 + 1) {
            pickerForRI = -1;
        }

        if (id == ID_ROOT_COND) {
            addChildCond(root);
        } else if (id == ID_ROOT_GROUP) {
            addChildGroup(root);
        } else if (id == ID_APPLY && valid) {
            Messages.SetFilterRule.sendToServer(preview);
            mc.displayGuiScreen(null);
        } else if (id == ID_CANCEL) {
            mc.displayGuiScreen(null);
        } else if (id >= ID_ITEM_BASE) {
            int ri   = (id - ID_ITEM_BASE) / 20;
            int sub  = (id - ID_ITEM_BASE) % 20;
            handleItemBtn(ri, sub);
        }
    }

    private void handleItemBtn(int ri, int sub) {
        if (ri >= rItems.size()) return;
        RItem item = rItems.get(ri);

        switch (item.type) {
            case COND: {
                CondNode c = (CondNode) item.node;
                switch (sub) {
                    case 0: c.not = !c.not; rebuild(); break;
                    case 1:
                        if (c.posIdx == POS_AT) { c.posIdx = 0; rebuild(); }
                        else { openPicker(ri, c); }
                        break;
                    case 2: c.negated = !c.negated; rebuild(); break;
                    case 3: syncFields(); remove(c); rebuild(); break;
                }
                break;
            }
            case GROUP_OPEN: {
                GroupNode g = (GroupNode) item.node;
                switch (sub) {
                    case 0: g.not = !g.not; rebuild(); break;
                    case 3: syncFields(); remove(g); rebuild(); break;
                    case 4: addChildCond(g); break;
                    case 5: addChildGroup(g); break;
                }
                break;
            }
            case CONN: {
                item.node.conn = (sub == 0) ? "and" : "or";
                rebuild();
                break;
            }
            default: break;
        }
    }

    private void addChildCond(GroupNode parent) {
        CondNode c = new CondNode(); c.parent = parent;
        if (!parent.children.isEmpty()) c.conn = "and";
        parent.children.add(c);
        rebuild();
    }

    private void addChildGroup(GroupNode parent) {
        GroupNode g = new GroupNode(); g.parent = parent;
        if (!parent.children.isEmpty()) g.conn = "and";
        CondNode first = new CondNode(); first.parent = g;
        g.children.add(first);
        parent.children.add(g);
        rebuild();
    }

    private void remove(ExprNode node) {
        GroupNode p = node.parent;
        if (p == null) return;
        p.children.remove(node);
        // Never leave a group empty
        if (p.children.isEmpty()) {
            CondNode fill = new CondNode(); fill.parent = p;
            p.children.add(fill);
        }
    }

    private void openPicker(int ri, CondNode c) {
        pickerForRI = ri;
        RItem item = rItems.get(ri);
        int px = px();
        int sy = vpTop() + item.vy - scroll;
        // Position picker just below the position button
        pickerSX = px + 10 + item.depth * IND + 32; // after NOT button
        pickerSY = sy + RH;
    }

    // ── Rendering ─────────────────────────────────────────────────────────

    @Override
    @SuppressWarnings("unchecked")
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();

        int px = px(), py = py();
        int vpTop = vpTop(), vpBot = vpBot();

        // Panel
        drawRect(px - 1, py - 1, px + PW + 1, py + PH + 1, 0xFF555555);
        drawRect(px, py, px + PW, py + PH, 0xFF1E1E1E);

        // Title + divider
        drawCenteredString(fontRendererObj, "§eFilter Editor", px + PW / 2, py + 6, 0xFFFFFF);
        drawRect(px + 8, py + 17, px + PW - 8, py + 18, 0xFF666666);

        // Scroll indicator
        if (listH > VP_H) {
            drawString(fontRendererObj, "§7[scroll]", px + PW - 72, py + 23, 0xFFFFFF);
        }

        // Viewport dividers
        drawRect(px + 8, vpTop - 1, px + PW - 8, vpTop,     0xFF666666);
        drawRect(px + 8, vpBot,     px + PW - 8, vpBot + 1, 0xFF666666);

        // Static buttons (no clipping)
        for (int bi = 0; bi < buttonList.size(); bi++) {
            GuiButton btn = (GuiButton) buttonList.get(bi);
            if (isStatic(btn.id)) btn.drawButton(mc, mouseX, mouseY);
        }

        // Clipped viewport
        ScaledResolution sr = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight);
        int sf = sr.getScaleFactor();
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor((px + 8) * sf, mc.displayHeight - vpBot * sf, (PW - 16) * sf, VP_H * sf);

        // Group backgrounds (left border + tint)
        for (int ri = 0; ri < rItems.size(); ri++) {
            RItem item = rItems.get(ri);
            if (item.type != RItemType.GROUP_OPEN) continue;
            GroupNode grp = (GroupNode) item.node;
            int gbX  = px + 8 + item.depth * IND;
            int gbT  = vpTop + item.vy - scroll;
            int gbB  = groupCloseScreenY(grp, vpTop);
            if (gbB > vpTop && gbT < vpBot) {
                int clT = Math.max(gbT, vpTop), clB = Math.min(gbB, vpBot);
                drawRect(gbX, clT, gbX + 2, clB, 0xFF4488CC);
                drawRect(gbX + 2, clT, px + PW - 8, clB, 0x18446699);
            }
        }

        // Item buttons (clipped)
        for (int bi = 0; bi < buttonList.size(); bi++) {
            GuiButton btn = (GuiButton) buttonList.get(bi);
            if (!isStatic(btn.id)) btn.drawButton(mc, mouseX, mouseY);
        }

        // Text fields (clipped)
        for (int ri = 0; ri < rItems.size(); ri++) {
            if (ri >= blockFields.size()) break;
            int fsy = fieldSY.get(ri);
            if (fsy + RH <= vpTop || fsy >= vpBot) continue;
            GuiTextField bf = blockFields.get(ri);
            if (bf != null) bf.drawTextBox();
            GuiTextField[] af = atFields.get(ri);
            if (af != null) for (GuiTextField f : af) f.drawTextBox();
        }

        // Group-close labels (clipped)
        for (int ri = 0; ri < rItems.size(); ri++) {
            RItem item = rItems.get(ri);
            if (item.type != RItemType.GROUP_CLOSE) continue;
            int sy = vpTop + item.vy - scroll;
            if (sy + GCH > vpTop && sy < vpBot) {
                int lx = px + 10 + item.depth * IND;
                drawString(fontRendererObj, "§7)", lx, sy + 1, 0xFFFFFF);
            }
        }

        GL11.glDisable(GL11.GL_SCISSOR_TEST);

        // Preview + status
        int pvy = py + O_PRV;
        String dp = preview.length() > 62 ? preview.substring(0, 59) + "..." : preview;
        drawString(fontRendererObj, "§7▷ " + dp, px + 10, pvy, 0xFFFFFF);
        drawString(fontRendererObj, status, px + 10, pvy + 12, 0xFFFFFF);

        // Position picker overlay (drawn last, no scissor)
        if (pickerForRI >= 0) drawPicker(mouseX, mouseY);
    }

    private boolean isStatic(int id) {
        return id == ID_ROOT_COND || id == ID_ROOT_GROUP || id == ID_APPLY || id == ID_CANCEL;
    }

    private int groupCloseScreenY(GroupNode grp, int vpTop) {
        for (RItem ri : rItems) {
            if (ri.type == RItemType.GROUP_CLOSE && ri.node == grp) {
                return vpTop + ri.vy + ri.height - scroll;
            }
        }
        return vpTop;
    }

    // ── Position picker overlay ────────────────────────────────────────────

    private static final int PICK_COLS = 2, PICK_ROWS = 6;
    private static final int PICK_BW = 92, PICK_BH = 14, PICK_GAP = 2;
    private static final int PICK_PW = PICK_COLS * (PICK_BW + PICK_GAP) - PICK_GAP + 8;
    private static final int PICK_PH = PICK_ROWS * (PICK_BH + PICK_GAP) - PICK_GAP + 8;

    private int pickerBtnX(int i) {
        int pxPick = Math.min(pickerSX, width  - PICK_PW - 2);
        return pxPick + 4 + (i % PICK_COLS) * (PICK_BW + PICK_GAP);
    }

    private int pickerBtnY(int i) {
        int pyPick = Math.min(pickerSY, height - PICK_PH - 2);
        return pyPick + 4 + (i / PICK_COLS) * (PICK_BH + PICK_GAP);
    }

    @SuppressWarnings("unchecked")
    private void drawPicker(int mouseX, int mouseY) {
        int pxPick = Math.min(pickerSX, width  - PICK_PW - 2);
        int pyPick = Math.min(pickerSY, height - PICK_PH - 2);

        drawRect(pxPick - 1, pyPick - 1, pxPick + PICK_PW + 1, pyPick + PICK_PH + 1, 0xFF888888);
        drawRect(pxPick,     pyPick,     pxPick + PICK_PW,     pyPick + PICK_PH,     0xFF222222);

        // Current selection (to highlight)
        int curPos = -1;
        if (pickerForRI >= 0 && pickerForRI < rItems.size()) {
            RItem ri = rItems.get(pickerForRI);
            if (ri.type == RItemType.COND) curPos = ((CondNode) ri.node).posIdx;
        }

        for (int i = 0; i < PICKER_LABELS.length; i++) {
            GuiButton btn = new GuiButton(ID_PICKER_BASE + i, pickerBtnX(i), pickerBtnY(i),
                PICK_BW, PICK_BH, PICKER_LABELS[i]);
            if (curPos == i) btn.packedFGColour = GREEN;
            btn.drawButton(mc, mouseX, mouseY);
        }
    }

    // ── Input ─────────────────────────────────────────────────────────────

    @Override
    protected void mouseClicked(int x, int y, int button) {
        // Picker clicks
        if (pickerForRI >= 0) {
            for (int i = 0; i < PICKER_LABELS.length; i++) {
                int bx = pickerBtnX(i), by = pickerBtnY(i);
                if (x >= bx && x < bx + PICK_BW && y >= by && y < by + PICK_BH) {
                    if (pickerForRI < rItems.size()) {
                        RItem ri = rItems.get(pickerForRI);
                        if (ri.type == RItemType.COND) ((CondNode) ri.node).posIdx = i;
                    }
                    pickerForRI = -1;
                    rebuild();
                    return;
                }
            }
            pickerForRI = -1; // clicked outside → just close
        }

        super.mouseClicked(x, y, button);

        // Text fields
        int vpTop = vpTop(), vpBot = vpBot();
        for (int ri = 0; ri < rItems.size(); ri++) {
            if (ri >= blockFields.size()) break;
            int fsy = fieldSY.get(ri);
            if (fsy + RH <= vpTop || fsy >= vpBot) continue;
            GuiTextField bf = blockFields.get(ri);
            if (bf != null) bf.mouseClicked(x, y, button);
            GuiTextField[] af = atFields.get(ri);
            if (af != null) for (GuiTextField f : af) f.mouseClicked(x, y, button);
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode == 1 && pickerForRI >= 0) { pickerForRI = -1; return; }
        super.keyTyped(typedChar, keyCode);
        int vpTop = vpTop(), vpBot = vpBot();
        for (int ri = 0; ri < rItems.size(); ri++) {
            if (ri >= blockFields.size()) break;
            int fsy = fieldSY.get(ri);
            if (fsy + RH <= vpTop || fsy >= vpBot) continue;
            GuiTextField bf = blockFields.get(ri);
            if (bf != null) bf.textboxKeyTyped(typedChar, keyCode);
            GuiTextField[] af = atFields.get(ri);
            if (af != null) for (GuiTextField f : af) f.textboxKeyTyped(typedChar, keyCode);
        }
        updatePreview();
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        for (GuiTextField bf : blockFields) if (bf != null) bf.updateCursorCounter();
        for (GuiTextField[] af : atFields) if (af != null) for (GuiTextField f : af) f.updateCursorCounter();
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        int dw = Mouse.getEventDWheel();
        if (dw != 0) {
            pickerForRI = -1;
            scroll -= Integer.signum(dw) * (RH + GAP);
            clampScroll();
            rebuild();
        }
    }

    @Override
    public boolean doesGuiPauseGame() { return false; }
}
