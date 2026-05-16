package com.recursive_pineapple.matter_manipulator.client.gui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.util.StatCollector;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

import com.recursive_pineapple.matter_manipulator.common.building.filter.FilterRuleParser;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.MMState.PendingAction;
import com.recursive_pineapple.matter_manipulator.common.networking.Messages;

import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

@SideOnly(Side.CLIENT)
public class GuiFilterEditor extends GuiScreen {

    // ── Panel layout ───────────────────────────────────────────────────────
    private static final int PANEL_W = 420, PANEL_H = 268;
    private static final int VIEWPORT_TOP_OFFSET = 49;
    private static final int VIEWPORT_H = 160;
    private static final int VIEWPORT_BOT_OFFSET = VIEWPORT_TOP_OFFSET + VIEWPORT_H;
    private static final int PREVIEW_Y_OFFSET = VIEWPORT_BOT_OFFSET + 5;
    private static final int STATUS_Y_OFFSET = PREVIEW_Y_OFFSET + 12;
    private static final int FOOTER_Y_OFFSET = STATUS_Y_OFFSET + 14;

    // ── Row heights ────────────────────────────────────────────────────────
    private static final int COND_ROW_H = 22;
    private static final int GROUP_ROW_H = 22;
    private static final int GROUP_CLOSE_H = 0;
    private static final int CONN_ROW_H = 22;
    private static final int ROW_GAP = 2;
    private static final int DEPTH_INDENT = 14;

    // ── Button ID ranges ───────────────────────────────────────────────────
    // Static (header + footer) buttons
    private static final int ID_ADD_COND_ROOT = 800;
    private static final int ID_ADD_GROUP_ROOT = 801;
    private static final int ID_APPLY = 802;
    private static final int ID_CANCEL = 803;

    // Per render-item: ID_ITEM_BASE + itemIndex * 20 + subButton
    // CONDITION: 1=POS_BTN 2=IS 3=REMOVE
    // GROUP_HEADER: 3=REMOVE 4=ADD_COND 5=ADD_GROUP
    // CONNECTOR: 0=AND 1=OR
    private static final int ID_ITEM_BASE = 1000;

    private static final int COLOR_ACTIVE = 0x55FF55;

    // ── Scrollbar ──────────────────────────────────────────────────────────
    private static final int SCROLLBAR_W = 6;

    // ── State ──────────────────────────────────────────────────────────────

    private final GroupNode root = new GroupNode();
    private final List<RenderItem> renderItems = new ArrayList<>();
    private final Map<Integer, CondRowUI> condRowUI = new HashMap<>();

    private int scroll = 0, totalListH = 0;
    private String preview = "", filterPreview = "", status = "";
    private boolean valid = false;
    private GuiButton applyBtn;

    private final FilterPickerOverlay picker = new FilterPickerOverlay();

    private boolean isDraggingScrollbar = false;
    private int scrollbarDragOffsetY = 0;

    // ── Block pick-from-world state ────────────────────────────────────────
    private static GuiFilterEditor pendingPickGui = null;
    private static int pendingPickCondIdx = -1;

    private String pendingBlockName = null;
    private int pendingBlockCondIdx = -1;

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

        filterPreview = FilterPreviewRenderer.build(root);

        if (FilterExprTree.hasEmptyBlock(root.children)) {
            status = "§e" + StatCollector.translateToLocal("mm.gui.filter_editor.status.fill_blocks");
            valid = false;
        } else {
            try {
                FilterRuleParser.parse(preview);
                status = "§a" + StatCollector.translateToLocal("mm.gui.filter_editor.status.valid");
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

        if (pendingBlockName != null && pendingBlockCondIdx >= 0 && pendingBlockCondIdx < renderItems.size()) {
            RenderItem item = renderItems.get(pendingBlockCondIdx);
            if (item.type == RenderType.CONDITION) {
                ((CondNode) item.node).block = pendingBlockName;
            }
            pendingBlockName = null;
            pendingBlockCondIdx = -1;
        }

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
        buttonList.add(
            new GuiButton(ID_ADD_COND_ROOT, px + 10, py + 20, 95, 20, "§a" + StatCollector.translateToLocal("mm.gui.filter_editor.add_condition"))
        );
        buttonList.add(
            new GuiButton(ID_ADD_GROUP_ROOT, px + 109, py + 20, 80, 20, "§b" + StatCollector.translateToLocal("mm.gui.filter_editor.add_group"))
        );
        applyBtn = new GuiButton(ID_APPLY, px + 8, py + FOOTER_Y_OFFSET, 90, 20, StatCollector.translateToLocal("mm.gui.filter_editor.apply"));
        buttonList.add(applyBtn);
        buttonList.add(
            new GuiButton(ID_CANCEL, px + PANEL_W - 98, py + FOOTER_Y_OFFSET, 90, 20, StatCollector.translateToLocal("mm.gui.filter_editor.cancel"))
        );
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
                default:
                    break;
            }
        }
    }

    private void addCondButtons(int idx, RenderItem item, int base, int leftX, int screenY, boolean visible, int panelX) {
        CondNode c = (CondNode) item.node;
        CondRowUI ui = condRowUI.computeIfAbsent(idx, k -> new CondRowUI());
        int x = leftX;

        String posLabel = FilterExprTree.posSummary(c) + (picker.linkedItem() == idx ? " ▲" : " ▼");
        if (visible) {
            buttonList.add(new GuiButton(base + 1, x, screenY, 90, COND_ROW_H - 2, posLabel));
        }
        x += 94;

        if (c.posAt) {
            ui.atFields = new GuiTextField[] {
                makeIntField(x, screenY, 20, c.atX),
                makeIntField(x + 23, screenY, 20, c.atY),
                makeIntField(x + 46, screenY, 20, c.atZ)
            };
            x += 72;
        }

        if (visible) {
            buttonList.add(new GuiButton(base + 2, x, screenY, 48, COND_ROW_H - 2, c.negated ? "is not" : "is"));
        }
        x += 52;

        int blockRight = panelX + PANEL_W - 14 - 16 - 20;
        int blockFieldY = screenY + 1;
        GuiTextField blockField = new GuiTextField(fontRendererObj, x, blockFieldY, blockRight - x, COND_ROW_H - 4);
        blockField.setMaxStringLength(200);
        blockField.setText(c.block);
        ui.blockField = blockField;
        ui.fieldScreenY = blockFieldY;

        GuiButton pickBtn = new GuiButton(base + 6, blockRight + 4, screenY, 16, COND_ROW_H - 2, "#");
        GuiButton removeBtn = new GuiButton(base + 3, blockRight + 24, screenY, 16, COND_ROW_H - 2, "X");
        removeBtn.packedFGColour = 0xFF5555;
        if (visible) {
            buttonList.add(pickBtn);
            buttonList.add(removeBtn);
        }
    }

    private void addGroupButtons(RenderItem item, int base, int leftX, int screenY, boolean visible, int panelX) {
        int x = leftX + 4;
        GuiButton addCondBtn = new GuiButton(
            base + 4,
            x,
            screenY,
            70,
            GROUP_ROW_H - 2,
            "§a" + StatCollector.translateToLocal("mm.gui.filter_editor.add_condition_short")
        );
        GuiButton addGroupBtn = new GuiButton(
            base + 5,
            x + 74,
            screenY,
            60,
            GROUP_ROW_H - 2,
            "§b" + StatCollector.translateToLocal("mm.gui.filter_editor.add_group_short")
        );
        GuiButton removeBtn = new GuiButton(base + 3, panelX + PANEL_W - 26, screenY, 16, GROUP_ROW_H - 2, "X");
        removeBtn.packedFGColour = 0xFF5555;
        if (visible) {
            buttonList.add(addCondBtn);
            buttonList.add(addGroupBtn);
            buttonList.add(removeBtn);
        }
    }

    private void addConnButtons(RenderItem item, int base, int screenY, boolean visible, int panelX) {
        int centerX = panelX + PANEL_W / 2 - 46;
        boolean isAnd = "and".equals(item.node.conn);

        GuiButton andBtn = new GuiButton(base, centerX, screenY, 44, CONN_ROW_H - 2, isAnd ? "[ AND ]" : " AND ");
        GuiButton orBtn = new GuiButton(base + 1, centerX + 48, screenY, 44, CONN_ROW_H - 2, isAnd ? "  OR  " : "[ OR ]");
        andBtn.packedFGColour = isAnd ? COLOR_ACTIVE : 0;
        orBtn.packedFGColour = isAnd ? 0 : COLOR_ACTIVE;
        if (visible) {
            buttonList.add(andBtn);
            buttonList.add(orBtn);
        }
    }

    private GuiTextField makeIntField(int x, int screenY, int w, int value) {
        IntTextField f = new IntTextField(fontRendererObj, x, screenY + 1, w, COND_ROW_H - 4, -9, 9);
        f.setMaxStringLength(2);
        f.setText(String.valueOf(value));
        return f;
    }

    // ── Actions ────────────────────────────────────────────────────────────

    @Override
    protected void actionPerformed(GuiButton button) {
        int id = button.id;
        if (picker.isOpen() && id != ID_ITEM_BASE + picker.linkedItem() * 20 + 1) {
            picker.close();
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
                if (picker.linkedItem() == itemIdx) {
                    picker.close();
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
            case 6:
                syncFieldsToNodes();
                pendingPickGui = this;
                pendingPickCondIdx = itemIdx;
                Messages.SetPendingAction.sendToServer(PendingAction.PICK_FILTER_BLOCK);
                mc.displayGuiScreen(null);
                break;
        }
    }

    @SideOnly(Side.CLIENT)
    public static void onBlockPicked(String blockName) {
        if (pendingPickGui == null || pendingPickCondIdx < 0) { return; }
        GuiFilterEditor gui = pendingPickGui;
        int condIdx = pendingPickCondIdx;
        pendingPickGui = null;
        pendingPickCondIdx = -1;
        gui.pendingBlockName = blockName;
        gui.pendingBlockCondIdx = condIdx;
        Minecraft.getMinecraft().displayGuiScreen(gui);
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
        RenderItem item = renderItems.get(itemIdx);
        picker.open(
            itemIdx,
            panelX() + 10 + item.depth * DEPTH_INDENT,
            viewportTop() + item.virtualY - scroll + COND_ROW_H
        );
        rebuild();
    }

    private CondNode pickerCondNode() {
        int idx = picker.linkedItem();
        if (idx < 0 || idx >= renderItems.size()) { return null; }
        RenderItem item = renderItems.get(idx);
        return item.type == RenderType.CONDITION ? (CondNode) item.node : null;
    }

    // ── Scrollbar ──────────────────────────────────────────────────────────

    private int scrollbarTrackX(int panelX) {
        return panelX + PANEL_W - 8;
    }

    private int scrollbarThumbH() {
        if (totalListH <= VIEWPORT_H) { return VIEWPORT_H; }
        return Math.max(10, VIEWPORT_H * VIEWPORT_H / totalListH);
    }

    private int scrollbarThumbY(int vpTop) {
        int thumbH = scrollbarThumbH();
        int travel = VIEWPORT_H - thumbH - 4;
        if (travel <= 0) { return vpTop; }
        return vpTop + scroll * travel / (totalListH - VIEWPORT_H);
    }

    private void setScrollFromThumbY(int thumbTop, int vpTop) {
        int thumbH = scrollbarThumbH();
        int travel = VIEWPORT_H - thumbH - 4;
        if (travel <= 0) {
            scroll = 0;
            return;
        }
        scroll = (thumbTop - vpTop) * (totalListH - VIEWPORT_H) / travel;
        clampScroll();
    }

    private void drawScrollbar(int panelX, int vpTop, int vpBot, int mouseX, int mouseY) {
        if (totalListH <= VIEWPORT_H) { return; }
        int tx = scrollbarTrackX(panelX);
        drawRect(tx, vpTop, tx + SCROLLBAR_W, vpBot, 0xFF2A2A2A);
        int thumbH = scrollbarThumbH();
        int thumbY = scrollbarThumbY(vpTop);
        boolean hover = isDraggingScrollbar || (mouseX >= tx && mouseX < tx + SCROLLBAR_W && mouseY >= thumbY && mouseY < thumbY + thumbH);
        drawRect(tx + 1, thumbY, tx + SCROLLBAR_W - 1, thumbY + thumbH, hover ? 0xFFAAAAAA : 0xFF888888);
    }

    private boolean handleScrollbarClick(int mouseX, int mouseY) {
        if (totalListH <= VIEWPORT_H) { return false; }
        int panelX = panelX();
        int tx = scrollbarTrackX(panelX);
        if (mouseX < tx || mouseX >= tx + SCROLLBAR_W) { return false; }
        int vpTop = viewportTop();
        int thumbH = scrollbarThumbH();
        int thumbY = scrollbarThumbY(vpTop);
        if (mouseY >= thumbY && mouseY < thumbY + thumbH) {
            isDraggingScrollbar = true;
            scrollbarDragOffsetY = mouseY - thumbY;
        } else if (mouseY >= vpTop && mouseY < viewportBottom()) {
            setScrollFromThumbY(mouseY - thumbH / 2, vpTop);
            rebuild();
        }
        return true;
    }

    // ── Rendering ──────────────────────────────────────────────────────────

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        drawPanelFrame(mouseX, mouseY);
        drawViewportContent(mouseX, mouseY);
        drawFooterText(mouseX, mouseY);
        if (picker.isOpen()) {
            picker.draw(mc, mouseX, mouseY, width, height, pickerCondNode(), COLOR_ACTIVE);
        }
    }

    private void drawPanelFrame(int mouseX, int mouseY) {
        int panelX = panelX(), panelY = panelY();
        int vpTop = viewportTop(), vpBot = viewportBottom();

        // Outer border + background
        drawRect(panelX - 1, panelY - 1, panelX + PANEL_W + 1, panelY + PANEL_H + 1, 0xFF555555);
        drawRect(panelX, panelY, panelX + PANEL_W, panelY + PANEL_H, 0xFF1E1E1E);

        drawCenteredString(
            fontRendererObj,
            "§e" + StatCollector.translateToLocal("mm.gui.filter_editor.title"),
            panelX + PANEL_W / 2,
            panelY + 6,
            0xFFFFFF
        );
        drawRect(panelX + 8, panelY + 15, panelX + PANEL_W - 8, panelY + 16, 0xFF666666);

        // Viewport divider lines
        drawRect(panelX + 8, vpTop - 5, panelX + PANEL_W - 8, vpTop - 4, 0xFF666666);
        drawRect(panelX + 8, vpBot, panelX + PANEL_W - 8, vpBot + 1, 0xFF666666);

        drawScrollbar(panelX, vpTop, vpBot - 4, mouseX, mouseY);

        // Static buttons (header + footer) drawn before scissor so they are never clipped
        boolean mouseInPickerFrame = picker.isMouseOver(mouseX, mouseY, width, height);
        int smx = mouseInPickerFrame ? -1 : mouseX;
        int smy = mouseInPickerFrame ? -1 : mouseY;
        for (GuiButton btn : buttonList) {
            if (isStaticButton(btn.id)) {
                btn.drawButton(mc, smx, smy);
            }
        }
    }

    private void drawViewportContent(int mouseX, int mouseY) {
        int panelX = panelX();
        int vpTop = viewportTop(), vpBot = viewportBottom();

        ScaledResolution sr = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight);
        int sf = sr.getScaleFactor();
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor((panelX + 8) * sf, mc.displayHeight - vpBot * sf, (PANEL_W - 16) * sf, (VIEWPORT_H + 4) * sf);

        drawGroupBackgrounds(panelX, vpTop, vpBot);

        boolean mouseInPicker = picker.isMouseOver(mouseX, mouseY, width, height);
        int vmx = mouseInPicker ? -1 : mouseX, vmy = mouseInPicker ? -1 : mouseY;
        for (GuiButton btn : buttonList) {
            if (!isStaticButton(btn.id)) {
                btn.drawButton(mc, vmx, vmy);
            }
        }

        drawCondTextFields(vpTop, vpBot);
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
    }

    private void drawGroupBackgrounds(int panelX, int vpTop, int vpBot) {
        for (RenderItem item : renderItems) {
            if (item.type != RenderType.GROUP_HEADER) {
                continue;
            }
            GroupNode grp = (GroupNode) item.node;
            int left = panelX + 8 + item.depth * DEPTH_INDENT;
            int top = vpTop + item.virtualY - scroll - 2;
            int bottom = findGroupCloseBottom(grp, vpTop) - 2;
            if (bottom <= vpTop || top >= vpBot) {
                continue;
            }

            int clippedBot = Math.min(bottom, vpBot);
            drawRect(left, top, left + 2, clippedBot, 0xFF55FFFF);
            drawRect(left + 2, top, panelX + PANEL_W - 9, clippedBot, 0x223dbaba);
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

    private void drawFooterText(int mouseX, int mouseY) {
        int panelX = panelX(), panelY = panelY();
        int textY = panelY + PREVIEW_Y_OFFSET;
        int startX = panelX + 10;
        String previewLabel = "§7▷ §r" + StatCollector.translateToLocal("mm.gui.filter_editor.preview");
        int labelW = fontRendererObj.getStringWidth(previewLabel);

        drawString(fontRendererObj, previewLabel, startX, textY, 0xFFFFFF);

        if (mouseX >= startX && mouseX < startX + labelW && mouseY >= textY && mouseY < textY + 10) {
            FilterPreviewRenderer.drawTooltip(
                fontRendererObj,
                FilterPreviewRenderer.buildFormattedLines(root),
                mouseX,
                mouseY,
                width,
                height
            );
        }

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

    // ── Input ──────────────────────────────────────────────────────────────

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        if (picker.isOpen() && picker.handleClick(mouseX, mouseY, width, height, pickerCondNode())) {
            rebuild();
            return;
        }
        if (button == 0 && handleScrollbarClick(mouseX, mouseY)) { return; }

        boolean pickerWasOpen = picker.isOpen();
        int pickerWasLinked = picker.linkedItem();
        super.mouseClicked(mouseX, mouseY, button);

        if (pickerWasOpen && picker.linkedItem() == pickerWasLinked) {
            picker.close();
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

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        if (isDraggingScrollbar) {
            setScrollFromThumbY(mouseY - scrollbarDragOffsetY, viewportTop());
            rebuild();
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
    protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode == 1 && picker.isOpen()) {
            picker.close();
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
            picker.close();
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
