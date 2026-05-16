package com.recursive_pineapple.matter_manipulator.client.gui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.ScaledResolution;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

import com.recursive_pineapple.matter_manipulator.common.building.filter.FilterRuleParser;
import com.recursive_pineapple.matter_manipulator.common.networking.Messages;

import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

@SideOnly(Side.CLIENT)
public class GuiFilterEditor extends GuiScreen {

    // ── Panel layout ───────────────────────────────────────────────────────
    private static final int PANEL_W = 420, PANEL_H = 252;
    private static final int VIEWPORT_TOP_OFFSET = 38;
    private static final int VIEWPORT_H = 140;
    private static final int VIEWPORT_BOT_OFFSET = VIEWPORT_TOP_OFFSET + VIEWPORT_H;
    private static final int PREVIEW_Y_OFFSET = VIEWPORT_BOT_OFFSET + 5;
    private static final int STATUS_Y_OFFSET = PREVIEW_Y_OFFSET + 12;
    private static final int FOOTER_Y_OFFSET = STATUS_Y_OFFSET + 14;

    // ── Row heights ────────────────────────────────────────────────────────
    private static final int COND_ROW_H = 18;
    private static final int GROUP_ROW_H = 14;
    private static final int GROUP_CLOSE_H = 10;
    private static final int CONN_ROW_H = 12;
    private static final int ROW_GAP = 2;
    private static final int DEPTH_INDENT = 10;

    // ── Button ID ranges ───────────────────────────────────────────────────
    // Static (header + footer) buttons
    private static final int ID_ADD_COND_ROOT = 800;
    private static final int ID_ADD_GROUP_ROOT = 801;
    private static final int ID_APPLY = 802;
    private static final int ID_CANCEL = 803;

    // Per render-item buttons: ID_ITEM_BASE + itemIndex * 20 + subButton
    // CONDITION: 1=POS_BTN 2=IS 3=REMOVE
    // GROUP_HEADER: 3=REMOVE 4=ADD_COND 5=ADD_GROUP
    // CONNECTOR: 0=AND 1=OR
    private static final int ID_ITEM_BASE = 1000;

    private static final int COLOR_ACTIVE = 0x55FF55;

    // ── Position picker layout ─────────────────────────────────────────────
    // 8 direction toggles in 2 columns, then an any/all row below
    private static final String[] PICKER_DIR_LABELS = {
        "north", "south", "west", "east", "above", "below", "self", "at X Y Z"
    };
    private static final int[] PICKER_DIR_BITS = {
        FilterExprTree.DIR_NORTH,
        FilterExprTree.DIR_SOUTH,
        FilterExprTree.DIR_WEST,
        FilterExprTree.DIR_EAST,
        FilterExprTree.DIR_ABOVE,
        FilterExprTree.DIR_BELOW,
        FilterExprTree.DIR_SELF,
        0 // 0 = posAt mode
    };
    private static final int PICKER_AT_IDX = 7; // index of "at X Y Z" in PICKER_DIR_*
    private static final int PICKER_BTN_W = 80, PICKER_BTN_H = 14, PICKER_GAP = 2;
    private static final int PICKER_COLS = 2;
    private static final int PICKER_DIR_ROWS = (PICKER_DIR_LABELS.length + PICKER_COLS - 1) / PICKER_COLS; // 4
    private static final int PICKER_W = PICKER_COLS * (PICKER_BTN_W + PICKER_GAP) - PICKER_GAP + 8;
    // dir rows + 1 any/all row, all with trailing gap, minus the very last gap, plus 8px padding
    private static final int PICKER_H = 8 + (PICKER_DIR_ROWS + 1) * (PICKER_BTN_H + PICKER_GAP) - PICKER_GAP;

    // ── Text field state for a single condition row ────────────────────────

    private static class CondRowUI {

        GuiTextField blockField;
        GuiTextField[] atFields; // [dx, dy, dz] when in "at" mode; null otherwise
        int fieldScreenY; // used for viewport visibility culling
    }

    // ── State ──────────────────────────────────────────────────────────────

    private final GroupNode root = new GroupNode();
    private final List<RenderItem> renderItems = new ArrayList<>();
    private final Map<Integer, CondRowUI> condRowUI = new HashMap<>();

    private int scroll = 0, totalListH = 0;
    private String preview = "", status = "";
    private boolean valid = false;
    private GuiButton applyBtn;

    // Position picker: -1 = closed; ≥0 = render-item index whose picker is open
    private int pickerForItem = -1;
    private int pickerScreenX, pickerScreenY;

    // ── Constructor ────────────────────────────────────────────────────────

    public GuiFilterEditor(String existingFilter) {
        CondNode first = new CondNode();
        first.parent = root;
        root.children.add(first);
    }

    // ── Screen-coordinate helpers ──────────────────────────────────────────

    private int panelX() {
        return (width - PANEL_W) / 2;
    }

    private int panelY() {
        return (height - PANEL_H) / 2;
    }

    private int viewportTop() {
        return panelY() + VIEWPORT_TOP_OFFSET;
    }

    private int viewportBottom() {
        return panelY() + VIEWPORT_BOT_OFFSET;
    }

    private void clampScroll() {
        scroll = Math.max(0, Math.min(scroll, Math.max(0, totalListH - VIEWPORT_H)));
    }

    // ── Tree → render list ─────────────────────────────────────────────────

    private void rebuildRenderList() {
        totalListH = FilterExprTree.flatten(
            root.children,
            renderItems,
            COND_ROW_H,
            GROUP_ROW_H,
            GROUP_CLOSE_H,
            CONN_ROW_H,
            ROW_GAP
        );
    }

    // ── Serialisation + validation ─────────────────────────────────────────

    private void updatePreview() {
        syncFieldsToNodes();
        preview = FilterExprTree.serialize(root.children);

        if (FilterExprTree.hasEmptyBlock(root.children)) {
            status = "§eFill in all block names";
            valid = false;
        } else {
            try {
                FilterRuleParser.parse(preview);
                status = "§aValid filter";
                valid = true;
            } catch (FilterRuleParser.ParseException e) {
                String msg = e.getMessage();
                status = "§c" + (msg.length() > 58 ? msg.substring(0, 55) + "..." : msg);
                valid = false;
            }
        }
        if (applyBtn != null) {
            applyBtn.enabled = valid;
        }
    }

    // ── Sync text fields → expression nodes ───────────────────────────────

    private void syncFieldsToNodes() {
        for (Map.Entry<Integer, CondRowUI> entry : condRowUI.entrySet()) {
            int idx = entry.getKey();
            if (idx >= renderItems.size()) {
                continue;
            }
            RenderItem item = renderItems.get(idx);
            if (item.type != RenderType.CONDITION) {
                continue;
            }

            CondNode c = (CondNode) item.node;
            CondRowUI ui = entry.getValue();
            c.block = ui.blockField.getText().trim();
            if (ui.atFields != null) {
                c.atX = parseIntSafe(ui.atFields[0].getText());
                c.atY = parseIntSafe(ui.atFields[1].getText());
                c.atZ = parseIntSafe(ui.atFields[2].getText());
            }
        }
    }

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ── GUI rebuild ────────────────────────────────────────────────────────

    @Override
    public void initGui() {
        super.initGui();
        rebuild();
    }

    private void rebuild() {
        syncFieldsToNodes();
        rebuildRenderList();
        clampScroll();

        buttonList.clear();
        condRowUI.clear();

        addStaticButtons();
        addItemButtons();
        updatePreview();
    }

    private void addStaticButtons() {
        int px = panelX(), py = panelY();
        buttonList.add(new GuiButton(ID_ADD_COND_ROOT, px + 10, py + 22, 95, 14, "§a+ Condition"));
        buttonList.add(new GuiButton(ID_ADD_GROUP_ROOT, px + 109, py + 22, 80, 14, "§9+ Group"));
        applyBtn = new GuiButton(ID_APPLY, px + 10, py + FOOTER_Y_OFFSET, 90, 16, "Apply");
        buttonList.add(applyBtn);
        buttonList.add(new GuiButton(ID_CANCEL, px + PANEL_W - 100, py + FOOTER_Y_OFFSET, 90, 16, "Cancel"));
    }

    private void addItemButtons() {
        int panelX = panelX();
        int vpTop = viewportTop(), vpBot = viewportBottom();

        for (int idx = 0; idx < renderItems.size(); idx++) {
            RenderItem item = renderItems.get(idx);
            int screenY = vpTop + item.virtualY - scroll;
            int leftX = panelX + 10 + item.depth * DEPTH_INDENT;
            boolean visible = screenY + item.rowHeight > vpTop && screenY < vpBot;
            int base = ID_ITEM_BASE + idx * 20;

            switch (item.type) {
                case CONDITION:
                    addCondButtons(idx, item, base, leftX, screenY, visible, panelX);
                    break;
                case GROUP_HEADER:
                    addGroupButtons(item, base, leftX, screenY, visible, panelX);
                    break;
                case CONNECTOR:
                    addConnButtons(item, base, screenY, visible, panelX);
                    break;
                case GROUP_FOOTER:
                    break; // visual only — no buttons
            }
        }
    }

    private void addCondButtons(
        int idx,
        RenderItem item,
        int base,
        int leftX,
        int screenY,
        boolean visible,
        int panelX
    ) {
        CondNode c = (CondNode) item.node;
        CondRowUI ui = condRowUI.computeIfAbsent(idx, k -> new CondRowUI());
        int x = leftX;

        // Position dropdown — label reflects current selection
        String posLabel = FilterExprTree.posSummary(c) + (pickerForItem == idx ? " ▲" : " ▼");
        GuiButton posBtn = new GuiButton(base + 1, x, screenY, 90, COND_ROW_H - 2, posLabel);
        if (visible) {
            buttonList.add(posBtn);
        }
        x += 94;

        // Inline at-offset fields shown when "at X Y Z" mode is active
        if (c.posAt) {
            GuiTextField dx = makeIntField(x, screenY, 26, c.atX);
            GuiTextField dy = makeIntField(x + 29, screenY, 26, c.atY);
            GuiTextField dz = makeIntField(x + 58, screenY, 26, c.atZ);
            ui.atFields = new GuiTextField[] {
                dx, dy, dz
            };
            x += 90;
        }

        GuiButton isBtn = new GuiButton(base + 2, x, screenY, 48, COND_ROW_H - 2, c.negated ? "is not" : "is");
        if (visible) {
            buttonList.add(isBtn);
        }
        x += 52;

        int blockRight = panelX + PANEL_W - 14 - 16;
        int blockFieldY = screenY + 2;
        GuiTextField blockField = new GuiTextField(fontRendererObj, x, blockFieldY, blockRight - x, COND_ROW_H - 4);
        blockField.setMaxStringLength(200);
        blockField.setText(c.block);
        ui.blockField = blockField;
        ui.fieldScreenY = blockFieldY;

        GuiButton removeBtn = new GuiButton(base + 3, blockRight + 4, screenY, 16, COND_ROW_H - 2, "X");
        if (visible) {
            buttonList.add(removeBtn);
        }
    }

    private void addGroupButtons(
        RenderItem item,
        int base,
        int leftX,
        int screenY,
        boolean visible,
        int panelX
    ) {
        GroupNode g = (GroupNode) item.node;
        int x = leftX;

        GuiButton addCondBtn = new GuiButton(base + 4, x, screenY, 70, GROUP_ROW_H - 2, "§a+ Cond");
        if (visible) {
            buttonList.add(addCondBtn);
        }
        x += 74;

        GuiButton addGroupBtn = new GuiButton(base + 5, x, screenY, 60, GROUP_ROW_H - 2, "§9+ Group");
        if (visible) {
            buttonList.add(addGroupBtn);
        }

        GuiButton removeBtn = new GuiButton(base + 3, panelX + PANEL_W - 26, screenY, 16, GROUP_ROW_H - 2, "X");
        if (visible) {
            buttonList.add(removeBtn);
        }
    }

    private void addConnButtons(RenderItem item, int base, int screenY, boolean visible, int panelX) {
        int centerX = panelX + PANEL_W / 2 - 46;
        boolean isAnd = "and".equals(item.node.conn);

        GuiButton andBtn = new GuiButton(base, centerX, screenY, 44, CONN_ROW_H - 2, isAnd ? "[AND]" : " AND ");
        GuiButton orBtn = new GuiButton(base + 1, centerX + 48, screenY, 44, CONN_ROW_H - 2, isAnd ? "  OR  " : "[ OR ]");
        andBtn.packedFGColour = isAnd ? COLOR_ACTIVE : 0;
        orBtn.packedFGColour = isAnd ? 0 : COLOR_ACTIVE;
        if (visible) {
            buttonList.add(andBtn);
            buttonList.add(orBtn);
        }
    }

    private GuiTextField makeIntField(int x, int screenY, int w, int value) {
        GuiTextField f = new GuiTextField(fontRendererObj, x, screenY + 2, w, COND_ROW_H - 4);
        f.setMaxStringLength(5);
        f.setText(String.valueOf(value));
        return f;
    }

    // ── Actions ────────────────────────────────────────────────────────────

    @Override
    protected void actionPerformed(GuiButton button) {
        int id = button.id;

        // Close picker unless the same position-button was clicked again
        if (pickerForItem >= 0 && id != ID_ITEM_BASE + pickerForItem * 20 + 1) {
            pickerForItem = -1;
        }

        if (id == ID_ADD_COND_ROOT) {
            addChildCond(root);
        } else if (id == ID_ADD_GROUP_ROOT) {
            addChildGroup(root);
        } else if (id == ID_APPLY && valid) {
            Messages.SetFilterRule.sendToServer(preview);
            mc.displayGuiScreen(null);
        } else if (id == ID_CANCEL) {
            mc.displayGuiScreen(null);
        } else if (id >= ID_ITEM_BASE) {
            handleItemButton((id - ID_ITEM_BASE) / 20, (id - ID_ITEM_BASE) % 20);
        }
    }

    private void handleItemButton(int itemIdx, int subBtn) {
        if (itemIdx >= renderItems.size()) { return; }
        RenderItem item = renderItems.get(itemIdx);
        switch (item.type) {
            case CONDITION:
                handleCondButton((CondNode) item.node, itemIdx, subBtn);
                break;
            case GROUP_HEADER:
                handleGroupButton((GroupNode) item.node, subBtn);
                break;
            case CONNECTOR:
                item.node.conn = (subBtn == 0) ? "and" : "or";
                rebuild();
                break;
            default:
                break;
        }
    }

    private void handleCondButton(CondNode c, int itemIdx, int subBtn) {
        switch (subBtn) {
            case 1:
                if (pickerForItem == itemIdx) {
                    pickerForItem = -1;
                    rebuild();
                } else {
                    openPicker(itemIdx);
                }
                break;
            case 2:
                c.negated = !c.negated;
                rebuild();
                break;
            case 3:
                syncFieldsToNodes();
                remove(c);
                rebuild();
                break;
        }
    }

    private void handleGroupButton(GroupNode g, int subBtn) {
        switch (subBtn) {
            case 3:
                syncFieldsToNodes();
                remove(g);
                rebuild();
                break;
            case 4:
                addChildCond(g);
                break;
            case 5:
                addChildGroup(g);
                break;
        }
    }

    private void addChildCond(GroupNode parent) {
        CondNode c = new CondNode();
        c.parent = parent;
        if (!parent.children.isEmpty()) {
            c.conn = "and";
        }
        parent.children.add(c);
        rebuild();
    }

    private void addChildGroup(GroupNode parent) {
        GroupNode g = new GroupNode();
        g.parent = parent;
        if (!parent.children.isEmpty()) {
            g.conn = "and";
        }
        CondNode placeholder = new CondNode();
        placeholder.parent = g;
        g.children.add(placeholder);
        parent.children.add(g);
        rebuild();
    }

    private void remove(ExprNode node) {
        GroupNode parent = node.parent;
        if (parent == null) { return; }
        parent.children.remove(node);
        // Keep every group non-empty so the UI never enters an invalid state
        if (parent.children.isEmpty()) {
            CondNode placeholder = new CondNode();
            placeholder.parent = parent;
            parent.children.add(placeholder);
        }
    }

    private void openPicker(int itemIdx) {
        pickerForItem = itemIdx;
        RenderItem item = renderItems.get(itemIdx);
        pickerScreenX = panelX() + 10 + item.depth * DEPTH_INDENT;
        pickerScreenY = viewportTop() + item.virtualY - scroll + COND_ROW_H;
        rebuild(); // updates the ▼→▲ label on the position button
    }

    // ── Rendering ──────────────────────────────────────────────────────────

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        drawPanelFrame(mouseX, mouseY);
        drawViewportContent(mouseX, mouseY);
        drawFooterText();
        if (pickerForItem >= 0) {
            drawPickerOverlay(mouseX, mouseY);
        }
    }

    private void drawPanelFrame(int mouseX, int mouseY) {
        int panelX = panelX(), panelY = panelY();
        int vpTop = viewportTop(), vpBot = viewportBottom();

        // Outer border + background
        drawRect(panelX - 1, panelY - 1, panelX + PANEL_W + 1, panelY + PANEL_H + 1, 0xFF555555);
        drawRect(panelX, panelY, panelX + PANEL_W, panelY + PANEL_H, 0xFF1E1E1E);

        drawCenteredString(fontRendererObj, "§eFilter Editor", panelX + PANEL_W / 2, panelY + 6, 0xFFFFFF);
        drawRect(panelX + 8, panelY + 17, panelX + PANEL_W - 8, panelY + 18, 0xFF666666);

        if (totalListH > VIEWPORT_H) {
            drawString(fontRendererObj, "§7[scroll]", panelX + PANEL_W - 72, panelY + 23, 0xFFFFFF);
        }

        // Viewport divider lines
        drawRect(panelX + 8, vpTop - 1, panelX + PANEL_W - 8, vpTop, 0xFF666666);
        drawRect(panelX + 8, vpBot, panelX + PANEL_W - 8, vpBot + 1, 0xFF666666);

        // Static buttons (header + footer) drawn before scissor so they are never clipped
        for (GuiButton btn : buttonList) {
            if (isStaticButton(btn.id)) {
                btn.drawButton(mc, mouseX, mouseY);
            }
        }
    }

    private void drawViewportContent(int mouseX, int mouseY) {
        int panelX = panelX();
        int vpTop = viewportTop(), vpBot = viewportBottom();

        ScaledResolution sr = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight);
        int sf = sr.getScaleFactor();
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(
            (panelX + 8) * sf,
            mc.displayHeight - vpBot * sf,
            (PANEL_W - 16) * sf,
            VIEWPORT_H * sf
        );

        drawGroupBackgrounds(panelX, vpTop, vpBot);

        for (GuiButton btn : buttonList) {
            if (!isStaticButton(btn.id)) {
                btn.drawButton(mc, mouseX, mouseY);
            }
        }

        drawCondTextFields(vpTop, vpBot);
        drawGroupCloseLabels(panelX, vpTop, vpBot);

        GL11.glDisable(GL11.GL_SCISSOR_TEST);
    }

    private void drawGroupBackgrounds(int panelX, int vpTop, int vpBot) {
        for (RenderItem item : renderItems) {
            if (item.type != RenderType.GROUP_HEADER) {
                continue;
            }
            GroupNode grp = (GroupNode) item.node;
            int left = panelX + 8 + item.depth * DEPTH_INDENT;
            int top = vpTop + item.virtualY - scroll;
            int bottom = findGroupCloseBottom(grp, vpTop);
            if (bottom <= vpTop || top >= vpBot) {
                continue;
            }

            int clippedTop = Math.max(top, vpTop);
            int clippedBot = Math.min(bottom, vpBot);
            drawRect(left, clippedTop, left + 2, clippedBot, 0xFF4488CC);
            drawRect(left + 2, clippedTop, panelX + PANEL_W - 8, clippedBot, 0x18446699);
        }
    }

    private void drawCondTextFields(int vpTop, int vpBot) {
        for (CondRowUI ui : condRowUI.values()) {
            if (ui.fieldScreenY + COND_ROW_H <= vpTop || ui.fieldScreenY >= vpBot) {
                continue;
            }
            if (ui.blockField != null) {
                ui.blockField.drawTextBox();
            }
            if (ui.atFields != null) {
                for (GuiTextField f : ui.atFields) {
                    f.drawTextBox();
                }
            }
        }
    }

    private void drawGroupCloseLabels(int panelX, int vpTop, int vpBot) {
        for (RenderItem item : renderItems) {
            if (item.type != RenderType.GROUP_FOOTER) {
                continue;
            }
            int screenY = vpTop + item.virtualY - scroll;
            if (screenY + GROUP_CLOSE_H <= vpTop || screenY >= vpBot) {
                continue;
            }
            drawString(
                fontRendererObj,
                "§7)",
                panelX + 10 + item.depth * DEPTH_INDENT,
                screenY + 1,
                0xFFFFFF
            );
        }
    }

    private void drawFooterText() {
        int panelX = panelX(), panelY = panelY();
        String displayPreview = preview.length() > 62 ? preview.substring(0, 59) + "..." : preview;
        drawString(fontRendererObj, "§7▷ " + displayPreview, panelX + 10, panelY + PREVIEW_Y_OFFSET, 0xFFFFFF);
        drawString(fontRendererObj, status, panelX + 10, panelY + STATUS_Y_OFFSET, 0xFFFFFF);
    }

    private boolean isStaticButton(int id) {
        return id == ID_ADD_COND_ROOT || id == ID_ADD_GROUP_ROOT || id == ID_APPLY || id == ID_CANCEL;
    }

    private int findGroupCloseBottom(GroupNode grp, int vpTop) {
        for (RenderItem item : renderItems) {
            if (item.type == RenderType.GROUP_FOOTER && item.node == grp) { return vpTop + item.virtualY + item.rowHeight - scroll; }
        }
        return vpTop;
    }

    // ── Position picker overlay ────────────────────────────────────────────

    private void drawPickerOverlay(int mouseX, int mouseY) {
        int px = clampedPickerX(), py = clampedPickerY();

        drawRect(px - 1, py - 1, px + PICKER_W + 1, py + PICKER_H + 1, 0xFF888888);
        drawRect(px, py, px + PICKER_W, py + PICKER_H, 0xFF222222);

        CondNode c = pickerCondNode();

        // Direction toggle buttons
        for (int i = 0; i < PICKER_DIR_LABELS.length; i++) {
            int bx = px + 4 + (i % PICKER_COLS) * (PICKER_BTN_W + PICKER_GAP);
            int by = py + 4 + (i / PICKER_COLS) * (PICKER_BTN_H + PICKER_GAP);
            GuiButton btn = new GuiButton(i, bx, by, PICKER_BTN_W, PICKER_BTN_H, PICKER_DIR_LABELS[i]);
            if (c != null) {
                boolean active = (i == PICKER_AT_IDX) ? c.posAt : (!c.posAt && (c.posMask & PICKER_DIR_BITS[i]) != 0);
                if (active) {
                    btn.packedFGColour = COLOR_ACTIVE;
                }
            }
            btn.drawButton(mc, mouseX, mouseY);
        }

        // Any / All toggle row (enabled only when 2+ directions are selected)
        boolean multiSelect = c != null && !c.posAt && Integer.bitCount(c.posMask) >= 2;
        int anyAllY = py + 4 + PICKER_DIR_ROWS * (PICKER_BTN_H + PICKER_GAP);
        int anyX = px + 4;
        int allX = px + 4 + PICKER_BTN_W + PICKER_GAP;

        GuiButton anyBtn = new GuiButton(PICKER_DIR_LABELS.length, anyX, anyAllY, PICKER_BTN_W, PICKER_BTN_H, "any");
        GuiButton allBtn = new GuiButton(PICKER_DIR_LABELS.length + 1, allX, anyAllY, PICKER_BTN_W, PICKER_BTN_H, "all");
        anyBtn.enabled = multiSelect;
        allBtn.enabled = multiSelect;
        if (multiSelect && c.posAny) {
            anyBtn.packedFGColour = COLOR_ACTIVE;
        }
        if (multiSelect && !c.posAny) {
            allBtn.packedFGColour = COLOR_ACTIVE;
        }
        anyBtn.drawButton(mc, mouseX, mouseY);
        allBtn.drawButton(mc, mouseX, mouseY);
    }

    private CondNode pickerCondNode() {
        if (pickerForItem < 0 || pickerForItem >= renderItems.size()) { return null; }
        RenderItem item = renderItems.get(pickerForItem);
        return item.type == RenderType.CONDITION ? (CondNode) item.node : null;
    }

    private int clampedPickerX() {
        return Math.min(pickerScreenX, width - PICKER_W - 2);
    }

    private int clampedPickerY() {
        return Math.min(pickerScreenY, height - PICKER_H - 2);
    }

    // ── Input ──────────────────────────────────────────────────────────────

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        if (pickerForItem >= 0 && handlePickerClick(mouseX, mouseY)) { return; }

        int currentPicker = pickerForItem;
        super.mouseClicked(mouseX, mouseY, button);

        if (currentPicker == pickerForItem) {
            pickerForItem = -1;
            rebuild();
        }

        int vpTop = viewportTop(), vpBot = viewportBottom();
        for (CondRowUI ui : condRowUI.values()) {
            if (ui.fieldScreenY + COND_ROW_H <= vpTop || ui.fieldScreenY >= vpBot) {
                continue;
            }
            if (ui.blockField != null) {
                ui.blockField.mouseClicked(mouseX, mouseY, button);
            }
            if (ui.atFields != null) {
                for (GuiTextField f : ui.atFields) {
                    f.mouseClicked(mouseX, mouseY, button);
                }
            }
        }
    }

    /**
     * Handles a click while the picker is open.
     * Direction buttons toggle; any/all buttons switch the combinator.
     * The picker stays open after each toggle.
     *
     * @return true if the click was inside the picker (consumed)
     */
    private boolean handlePickerClick(int mouseX, int mouseY) {
        int px = clampedPickerX(), py = clampedPickerY();
        CondNode c = pickerCondNode();

        // Direction toggle buttons
        for (int i = 0; i < PICKER_DIR_LABELS.length; i++) {
            int bx = px + 4 + (i % PICKER_COLS) * (PICKER_BTN_W + PICKER_GAP);
            int by = py + 4 + (i / PICKER_COLS) * (PICKER_BTN_H + PICKER_GAP);
            if (mouseX >= bx && mouseX < bx + PICKER_BTN_W && mouseY >= by && mouseY < by + PICKER_BTN_H) {
                if (c != null) {
                    if (i == PICKER_AT_IDX) {
                        c.posAt = !c.posAt;
                        if (!c.posAt && c.posMask == 0) {
                            c.posMask = FilterExprTree.DIR_NORTH;
                        }
                    } else {
                        int bit = PICKER_DIR_BITS[i];
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
                rebuild(); // picker stays open; label + highlights update
                return true;
            }
        }

        // Any / All buttons (only active when 2+ directions selected)
        boolean multiSelect = c != null && !c.posAt && Integer.bitCount(c.posMask) >= 2;
        if (multiSelect) {
            int anyAllY = py + 4 + PICKER_DIR_ROWS * (PICKER_BTN_H + PICKER_GAP);
            if (mouseY >= anyAllY && mouseY < anyAllY + PICKER_BTN_H) {
                int anyX = px + 4;
                int allX = px + 4 + PICKER_BTN_W + PICKER_GAP;
                if (mouseX >= anyX && mouseX < anyX + PICKER_BTN_W) {
                    c.posAny = true;
                    rebuild();
                    return true;
                }
                if (mouseX >= allX && mouseX < allX + PICKER_BTN_W) {
                    c.posAny = false;
                    rebuild();
                    return true;
                }
            }
        }

        return false; // outside picker — caller handles closing
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode == 1 && pickerForItem >= 0) {
            pickerForItem = -1;
            rebuild();
            return;
        }
        super.keyTyped(typedChar, keyCode);

        int vpTop = viewportTop(), vpBot = viewportBottom();
        for (CondRowUI ui : condRowUI.values()) {
            if (ui.fieldScreenY + COND_ROW_H <= vpTop || ui.fieldScreenY >= vpBot) {
                continue;
            }
            if (ui.blockField != null) {
                ui.blockField.textboxKeyTyped(typedChar, keyCode);
            }
            if (ui.atFields != null) {
                for (GuiTextField f : ui.atFields) {
                    f.textboxKeyTyped(typedChar, keyCode);
                }
            }
        }
        updatePreview();
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        for (CondRowUI ui : condRowUI.values()) {
            if (ui.blockField != null) {
                ui.blockField.updateCursorCounter();
            }
            if (ui.atFields != null) {
                for (GuiTextField f : ui.atFields) {
                    f.updateCursorCounter();
                }
            }
        }
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        int dWheel = Mouse.getEventDWheel();
        if (dWheel != 0) {
            pickerForItem = -1;
            scroll -= Integer.signum(dWheel) * (COND_ROW_H + ROW_GAP);
            clampScroll();
            rebuild();
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
