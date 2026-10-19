package com.recursive_pineapple.matter_manipulator.client.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.StatCollector;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

import com.gtnewhorizons.modularui.api.drawable.AdaptableUITexture;
import com.gtnewhorizons.modularui.api.drawable.IDrawable;
import com.recursive_pineapple.matter_manipulator.MMMod;
import com.recursive_pineapple.matter_manipulator.client.nei.BlueprintNEIBookmarks;
import com.recursive_pineapple.matter_manipulator.common.blueprint.BlueprintClientCache;
import com.recursive_pineapple.matter_manipulator.common.blueprint.BlueprintMaterialExport;
import com.recursive_pineapple.matter_manipulator.common.blueprint.BlueprintMaterials;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.ItemMatterManipulator;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.Location;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.MMState;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.MMState.PlaceMode;
import com.recursive_pineapple.matter_manipulator.common.networking.Messages;
import com.recursive_pineapple.matter_manipulator.common.networking.Messages.BlueprintEntry;
import com.recursive_pineapple.matter_manipulator.common.networking.Messages.BlueprintOperation;
import com.recursive_pineapple.matter_manipulator.common.networking.Messages.BlueprintRequestData;
import com.recursive_pineapple.matter_manipulator.common.networking.Messages.BlueprintStatus;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

/** Client-only library styled like the manipulator's transparent editors. All operations are server-authorized. */
@SideOnly(Side.CLIENT)
public class BlueprintLibraryScreen extends GuiScreen {

    private static final int SAVE = 1, SELECT = 2, RENAME = 3, DELETE = 4, REFRESH = 6, DONE = 7, PREV = 8, NEXT = 9, INFO = 10, COMPONENTS = 11,
        BOOKMARKS = 12, PREVIEW = 13;
    private static final int TEXT = 0xCCCCCC, MUTED_TEXT = 0x999999, PANEL_BACKGROUND = 0x80333333;
    private static final IDrawable TEXT_FIELD_BACKGROUND = AdaptableUITexture.of("modularui2:gui/background/display_small", 18, 18, 1);
    private final List<BlueprintEntry> entries = new ArrayList<>();
    private final List<BlueprintEntry> filtered = new ArrayList<>();
    private GuiTextField search, name;
    private BlueprintLibraryLayout layout;
    private int page, labelWidth;
    private boolean showInfo, statusError;
    private int listRequest = -1, operationRequest = -1;
    private long listDeadline, operationDeadline;
    private String selectedId, confirmDelete, status = "";
    private String operationId;
    private BlueprintOperation operation;
    private boolean opened;
    private final List<BlueprintMaterials.Entry> materials = new ArrayList<>();
    private final List<ItemStack> materialIcons = new ArrayList<>();
    private int bookmarksRequest = -1, bookmarkBytesReceived;
    private long bookmarksDeadline;
    private byte[] bookmarkData;
    private boolean bookmarksAdded;
    private String materialsId, materialsName, materialsStatus = "";
    private int materialsPage, materialsTotalTypes = -1, materialsRequest = -1;
    private long materialsDeadline;
    private boolean materialsError, materialsLoaded, materialsCopyPatterns;

    public BlueprintLibraryScreen() {}

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        String oldSearch = search == null ? "" : search.getText();
        String oldName = name == null ? "" : name.getText();
        boolean focusName = name != null && name.isFocused();
        int oldRows = layout == null ? 0 : layout.rows;
        layout = new BlueprintLibraryLayout(width, height);
        labelWidth = Math.min(
            layout.contentWidth / 3,
            Math.max(44, Math.max(fontRendererObj.getStringWidth(text("search_label")), fontRendererObj.getStringWidth(text("name"))) + 8)
        );
        search = makeField(layout.searchY, oldSearch);
        search.setFocused(!focusName);
        name = makeField(layout.nameY, oldName);
        name.setFocused(focusName);
        buttonList.clear();
        add(SAVE, layout.buttonLeft(0, 4), layout.actionsY, layout.buttonWidth(0, 4), "save");
        add(SELECT, layout.buttonLeft(1, 4), layout.actionsY, layout.buttonWidth(1, 4), "select");
        add(RENAME, layout.buttonLeft(2, 4), layout.actionsY, layout.buttonWidth(2, 4), "rename");
        add(DELETE, layout.buttonLeft(3, 4), layout.actionsY, layout.buttonWidth(3, 4), "delete");
        add(REFRESH, layout.buttonLeft(0, 4), layout.footerY, layout.buttonWidth(0, 4), "refresh");
        add(COMPONENTS, layout.buttonLeft(1, 4), layout.footerY, layout.buttonWidth(1, 4), "materials");
        add(PREVIEW, layout.buttonLeft(2, 4), layout.footerY, layout.buttonWidth(2, 4), "preview");
        add(DONE, layout.buttonLeft(3, 4), layout.footerY, layout.buttonWidth(3, 4), "done");
        add(BOOKMARKS, layout.buttonLeft(1, 3), layout.footerY, layout.buttonWidth(1, 3), "bookmarks.add");
        buttonList.add(new LibraryButton(PREV, layout.contentLeft, layout.pageY, 24, "<"));
        buttonList.add(new LibraryButton(NEXT, layout.contentLeft + layout.contentWidth - 24, layout.pageY, 24, ">"));
        buttonList.add(new LibraryButton(INFO, layout.left + layout.width - 28, layout.top + 5, 18, "i"));
        filter();
        if (!opened) {
            opened = true;
            refresh();
        }
        if (inMaterials() && oldRows != layout.rows) {
            // A resize supersedes the old page; its late reply must not populate the new geometry.
            int resizedPage = (int) Math.min(Integer.MAX_VALUE, (long) materialsPage * oldRows / layout.rows);
            materialsRequest = -1;
            materialsTotalTypes = -1;
            requestMaterials(resizedPage);
        }
        updateButtons();
    }

    private void add(int id, int x, int y, int w, String key) {
        buttonList.add(new LibraryButton(id, x, y, w, text(key)));
    }

    private static String text(String key, Object... arguments) {
        return StatCollector.translateToLocalFormatted("mm.blueprint." + key, arguments);
    }

    private GuiTextField makeField(int y, String value) {
        GuiTextField field = new GuiTextField(
            fontRendererObj,
            layout.contentLeft + labelWidth + 4,
            y + 5,
            layout.contentWidth - labelWidth - 8,
            fontRendererObj.FONT_HEIGHT
        );
        field.setMaxStringLength(64);
        field.setEnableBackgroundDrawing(false);
        field.setTextColor(TEXT);
        field.setText(value);
        return field;
    }

    private boolean hasWorldSource() {
        ItemStack held = mc.thePlayer == null ? null : mc.thePlayer.getHeldItem();
        if (held == null || !(held.getItem() instanceof ItemMatterManipulator)) return false;
        MMState state = ItemMatterManipulator.getState(held);
        return state.config.placeMode == PlaceMode.COPYING && !state.config.isBlueprint() &&
            Location.areCompatible(state.config.coordA, state.config.coordB) &&
            mc.theWorld != null &&
            state.config.coordA.isInWorld(mc.theWorld);
    }

    private BlueprintEntry selected() {
        for (BlueprintEntry entry : entries)
            if (entry.id.equals(selectedId)) return entry;
        return null;
    }

    private int pages() {
        return Math.max(1, (filtered.size() + layout.rows - 1) / layout.rows);
    }

    private void filter() {
        filtered.clear();
        String needle = search.getText().trim().toLowerCase(Locale.ROOT);
        for (BlueprintEntry entry : entries)
            if (entry.name.toLowerCase(Locale.ROOT).contains(needle)) filtered.add(entry);
        page = Math.max(0, Math.min(page, pages() - 1));
    }

    private void refresh() {
        listRequest = BlueprintClientCache.nextRequestId();
        listDeadline = System.currentTimeMillis() + 15000;
        status = text("loading");
        statusError = false;
        Messages.BlueprintRequest.sendToServer(new BlueprintRequestData(BlueprintOperation.LIST, listRequest, null, null));
    }

    private boolean inMaterials() {
        return materialsId != null;
    }

    private int materialsPages() {
        return Math.max(1, (int) (((long) Math.max(0, materialsTotalTypes) + layout.rows - 1) / layout.rows));
    }

    private void openMaterials() {
        BlueprintEntry entry = selected();
        if (entry == null || operation != null || listRequest != -1 || materialsRequest != -1) return;
        materialsId = entry.id;
        materialsName = entry.name;
        materialsTotalTypes = -1;
        bookmarksAdded = false;
        showInfo = false;
        requestMaterials(0);
    }

    private void openPreview() {
        BlueprintEntry entry = selected();
        if (entry == null || operation != null || listRequest != -1) return;
        showInfo = false;
        mc.displayGuiScreen(new BlueprintPreviewScreen(this, entry.id, entry.name));
    }

    private void backToLibrary() {
        materialsRequest = -1;
        bookmarksRequest = -1;
        bookmarkData = null;
        materialsId = null;
        materials.clear();
        materialIcons.clear();
        showInfo = false;
        updateButtons();
    }

    private void requestMaterials(int nextPage) {
        if (!inMaterials() || materialsRequest != -1 || operation != null || listRequest != -1) return;
        materialsPage = Math.max(0, materialsTotalTypes < 0 ? nextPage : Math.min(nextPage, materialsPages() - 1));
        materialsRequest = BlueprintClientCache.nextRequestId();
        materialsDeadline = System.currentTimeMillis() + 15000;
        materialsStatus = text("materials_loading");
        materialsError = false;
        materialsLoaded = false;
        materials.clear();
        materialIcons.clear();
        ItemStack held = mc.thePlayer == null ? null : mc.thePlayer.getHeldItem();
        materialsCopyPatterns = held != null && held.getItem() instanceof ItemMatterManipulator &&
            ItemMatterManipulator.getState(held).config.shouldCopyAEPatterns;
        Messages.BlueprintRequest.sendToServer(
            new BlueprintRequestData(BlueprintOperation.MATERIALS, materialsRequest, materialsId, null, materialsPage, layout.rows)
        );
        updateButtons();
    }

    private void requestBookmarks() {
        if (!inMaterials() || materialsRequest != -1 || bookmarksRequest != -1 || bookmarksAdded || !BlueprintNEIBookmarks.isAvailable()) return;
        bookmarksRequest = BlueprintClientCache.nextRequestId();
        bookmarksDeadline = System.currentTimeMillis() + 15000;
        bookmarkData = null;
        bookmarkBytesReceived = 0;
        materialsStatus = text("bookmarks.loading");
        materialsError = false;
        Messages.BlueprintRequest.sendToServer(new BlueprintRequestData(BlueprintOperation.BOOKMARKS, bookmarksRequest, materialsId, null));
        updateButtons();
    }

    private void bookmarkError(String translationKey, String detail) {
        bookmarksRequest = -1;
        bookmarkData = null;
        bookmarkBytesReceived = 0;
        materialsStatus = StatCollector.translateToLocal(translationKey);
        if (!detail.isEmpty()) materialsStatus += " " + detail;
        materialsError = true;
        updateButtons();
    }

    private boolean validName() {
        String value = name.getText();
        if (value.trim().isEmpty() || value.length() > 64) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isISOControl(c)) return false;
            if (Character.isHighSurrogate(c)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) return false;
            } else if (Character.isLowSurrogate(c)) return false;
        }
        return true;
    }

    private void updateButtons() {
        if (layout == null || search == null || name == null) return;
        boolean viewingMaterials = inMaterials();
        boolean busy = operation != null || listRequest != -1 || materialsRequest != -1 || bookmarksRequest != -1;
        boolean neiAvailable = viewingMaterials && BlueprintNEIBookmarks.isAvailable();
        boolean hasSelection = selected() != null;
        boolean canSave = hasWorldSource();
        for (Object object : buttonList) {
            GuiButton button = (GuiButton) object;
            button.visible = button.id == BOOKMARKS ? viewingMaterials : !viewingMaterials || switch (button.id) {
                case SAVE, SELECT, RENAME, DELETE, COMPONENTS, PREVIEW -> false;
                default -> true;
            };
            button.enabled = button.visible && switch (button.id) {
                case SAVE -> !busy && canSave && validName();
                case SELECT, DELETE -> !busy && hasSelection;
                case RENAME -> !busy && hasSelection && validName();
                case COMPONENTS, PREVIEW -> !busy && hasSelection;
                case BOOKMARKS -> !busy && materialsLoaded && materialsTotalTypes > 0 && !bookmarksAdded && neiAvailable;
                case REFRESH -> !busy;
                case PREV -> viewingMaterials ? !busy && materialsPage > 0 : page > 0;
                case NEXT -> viewingMaterials ? !busy && materialsPage + 1 < materialsPages() : page + 1 < pages();
                default -> true;
            };
            if (button.id == DELETE) button.displayString = text(confirmDelete == null ? "delete" : "confirm");
            if (button.id == DONE) button.displayString = text(viewingMaterials ? "back" : "done");
            if (button.id == BOOKMARKS) button.displayString = text(bookmarksAdded ? "bookmarks.added_button" : "bookmarks.add");
            if (button.id == REFRESH || button.id == DONE) {
                // The library footer also holds Components and Preview; the materials footer only Add to NEI.
                int columns = viewingMaterials ? 3 : 4;
                int column = button.id == REFRESH ? 0 : columns - 1;
                button.xPosition = layout.buttonLeft(column, columns);
                button.width = layout.buttonWidth(column, columns);
            }
        }
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (!button.enabled || !button.visible) return;
        if (inMaterials()) {
            switch (button.id) {
                case REFRESH -> requestMaterials(materialsPage);
                case BOOKMARKS -> requestBookmarks();
                case DONE -> backToLibrary();
                case PREV -> requestMaterials(materialsPage - 1);
                case NEXT -> requestMaterials(materialsPage + 1);
                case INFO -> showInfo = !showInfo;
                default -> {}
            }
            updateButtons();
            return;
        }
        if (button.id != DELETE) confirmDelete = null;
        switch (button.id) {
            case SAVE -> send(BlueprintOperation.SAVE);
            case SELECT -> send(BlueprintOperation.SELECT);
            case RENAME -> send(BlueprintOperation.RENAME);
            case DELETE -> {
                BlueprintEntry entry = selected();
                if (entry == null) return;
                if (entry.id.equals(confirmDelete)) {
                    confirmDelete = null;
                    send(BlueprintOperation.DELETE);
                } else {
                    confirmDelete = entry.id;
                    status = text("confirm_delete", entry.name);
                    statusError = true;
                }
            }
            case REFRESH -> refresh();
            case COMPONENTS -> openMaterials();
            case PREVIEW -> openPreview();
            case DONE -> mc.displayGuiScreen(null);
            case PREV -> page--;
            case NEXT -> page++;
            case INFO -> showInfo = !showInfo;
            default -> {}
        }
        updateButtons();
    }

    private void send(BlueprintOperation nextOperation) {
        operation = nextOperation;
        operationId = selectedId;
        operationDeadline = System.currentTimeMillis() + 15000;
        status = text("working");
        statusError = false;
        if (operation == BlueprintOperation.SELECT) {
            operationRequest = BlueprintClientCache.select(selectedId);
        } else {
            operationRequest = BlueprintClientCache.nextRequestId();
            Messages.BlueprintRequest.sendToServer(new BlueprintRequestData(operation, operationRequest, selectedId, name.getText()));
        }
    }

    @Override
    public void updateScreen() {
        search.updateCursorCounter();
        name.updateCursorCounter();
        long now = System.currentTimeMillis();
        if (bookmarksRequest != -1 && now > bookmarksDeadline) bookmarkError("mm.blueprint.timeout", "");
        if (materialsRequest != -1 && now > materialsDeadline) {
            materialsRequest = -1;
            materialsStatus = text("timeout");
            materialsError = true;
        }
        if (listRequest != -1 && now > listDeadline) {
            listRequest = -1;
            status = text("timeout");
            statusError = true;
        }
        if (operation != null && now > operationDeadline) {
            operation = null;
            operationRequest = -1;
            status = text("timeout");
            statusError = true;
        }
        ItemStack held = mc.thePlayer == null ? null : mc.thePlayer.getHeldItem();
        if (
            held == null || !(held.getItem() instanceof ItemMatterManipulator) ||
                !ItemMatterManipulator.getState(held).hasCap(ItemMatterManipulator.ALLOW_COPYING)
        ) {
            mc.displayGuiScreen(null);
            return;
        }
        updateButtons();
    }

    @Override
    protected void keyTyped(char character, int key) {
        if (key == Keyboard.KEY_ESCAPE) {
            if (inMaterials()) {
                backToLibrary();
            } else if (showInfo) {
                showInfo = false;
            } else if (confirmDelete != null) {
                confirmDelete = null;
                status = "";
                statusError = false;
                updateButtons();
            } else
                mc.displayGuiScreen(null);
            return;
        }
        if (inMaterials()) return;
        if (key == Keyboard.KEY_TAB) {
            boolean focusName = search.isFocused();
            search.setFocused(!focusName);
            name.setFocused(focusName);
            return;
        }
        if (search.textboxKeyTyped(character, key)) {
            page = 0;
            filter();
            confirmDelete = null;
        } else if (name.textboxKeyTyped(character, key)) confirmDelete = null;
        updateButtons();
    }

    @Override
    protected void mouseClicked(int x, int y, int button) {
        super.mouseClicked(x, y, button);
        if (inMaterials()) {
            if (!layout.overInfo(x, y)) showInfo = false;
            return;
        }
        clickField(search, layout.searchY, x, y, button);
        clickField(name, layout.nameY, x, y, button);
        if (!layout.overInfo(x, y)) showInfo = false;
        int row = layout.rowAt(x, y);
        if (button == 0 && row >= 0) {
            int index = page * layout.rows + row;
            if (index < filtered.size()) {
                BlueprintEntry entry = filtered.get(index);
                selectedId = entry.id;
                name.setText(entry.name);
                confirmDelete = null;
                status = "";
                statusError = false;
                updateButtons();
            }
        }
    }

    private void clickField(GuiTextField field, int top, int mouseX, int mouseY, int button) {
        int left = layout.contentLeft + labelWidth;
        int right = layout.contentLeft + layout.contentWidth;
        if (mouseX >= left && mouseX < right && mouseY >= top && mouseY < top + 18) {
            field.mouseClicked(Math.max(left + 4, Math.min(right - 5, mouseX)), top + 5, button);
        } else {
            field.mouseClicked(mouseX, mouseY, button);
        }
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0) {
            int direction = wheel < 0 ? 1 : -1;
            if (inMaterials()) {
                int nextPage = Math.max(0, Math.min(materialsPages() - 1, materialsPage + direction));
                if (nextPage != materialsPage) requestMaterials(nextPage);
            } else {
                page = Math.max(0, Math.min(pages() - 1, page + direction));
            }
        }
        updateButtons();
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        int right = layout.left + layout.width;
        int contentRight = layout.contentLeft + layout.contentWidth;
        BlueprintGuiRendering.panel(layout.left - 1, layout.top - 1, right + 1, layout.top + layout.height + 1, 0x70666666);
        BlueprintGuiRendering.panel(layout.left, layout.top, right, layout.top + layout.height, 0xB0000000);
        BlueprintGuiRendering.panel(layout.left, layout.top, right, layout.top + 28, PANEL_BACKGROUND);
        drawString(
            fontRendererObj,
            fontRendererObj.trimStringToWidth(text(inMaterials() ? "materials_title" : "title"), layout.contentWidth - 24),
            layout.contentLeft,
            layout.top + 10,
            TEXT
        );
        if (inMaterials()) {
            drawMaterials(mouseX, mouseY, partialTicks);
            return;
        }
        drawField(search, layout.searchY, text("search_label"), partialTicks);
        drawField(name, layout.nameY, text("name"), partialTicks);

        BlueprintGuiRendering.panel(layout.contentLeft, layout.listHeaderY, contentRight, layout.listHeaderY + 14, PANEL_BACKGROUND);
        drawString(fontRendererObj, text("column_name"), layout.contentLeft + 6, layout.listHeaderY + 3, TEXT);
        drawRightText(text("column_dimensions"), contentRight - 6, layout.listHeaderY + 3, MUTED_TEXT);
        int hoveredRow = layout.rowAt(mouseX, mouseY);
        for (int row = 0; row < layout.rows; row++) {
            int index = page * layout.rows + row;
            int y = layout.listTop + row * BlueprintLibraryLayout.ROW_HEIGHT;
            boolean selected = index < filtered.size() && filtered.get(index).id.equals(selectedId);
            int background = selected ? 0xB04A4A4A : row == hoveredRow ? PANEL_BACKGROUND : (row % 2 == 0 ? 0x50222222 : 0x30000000);
            BlueprintGuiRendering.panel(layout.contentLeft, y, contentRight, y + BlueprintLibraryLayout.ROW_HEIGHT - 1, background);
            if (index >= filtered.size()) continue;
            BlueprintEntry entry = filtered.get(index);
            if (selected) BlueprintGuiRendering.panel(layout.contentLeft, y + 2, layout.contentLeft + 2, y + BlueprintLibraryLayout.ROW_HEIGHT - 3, 0xFFCCCCCC);
            String dimensions = dimensions(entry);
            int nameWidth = layout.contentWidth - fontRendererObj.getStringWidth(dimensions) - 22;
            drawString(fontRendererObj, fontRendererObj.trimStringToWidth(entry.name, nameWidth), layout.contentLeft + 7, y + 3, selected ? 0xFFFFFF : TEXT);
            drawRightText(dimensions, contentRight - 6, y + 3, MUTED_TEXT);
            drawString(fontRendererObj, text("blocks", entry.blockCount), layout.contentLeft + 7, y + 14, MUTED_TEXT);
        }
        if (filtered.isEmpty()) drawCenteredString(fontRendererObj, text("empty"), width / 2, layout.listTop + 8, MUTED_TEXT);
        drawCenteredString(fontRendererObj, text("page", page + 1, pages(), filtered.size()), width / 2, layout.pageY + 5, TEXT);
        String footer = status.isEmpty() ? text("hint") : status;
        drawCenteredString(
            fontRendererObj,
            fontRendererObj.trimStringToWidth(footer, layout.contentWidth),
            width / 2,
            layout.statusY,
            statusError ? 0xFFCC88 : MUTED_TEXT
        );
        super.drawScreen(mouseX, mouseY, partialTicks);

        if (showInfo || layout.overInfo(mouseX, mouseY)) {
            List<String> help = new ArrayList<>(fontRendererObj.listFormattedStringToWidth(text("help"), Math.min(layout.contentWidth, 280)));
            help.add("");
            help.addAll(fontRendererObj.listFormattedStringToWidth(text("warning"), Math.min(layout.contentWidth, 280)));
            drawHoveringText(help, mouseX, mouseY, fontRendererObj);
        } else if (!status.isEmpty() && mouseX >= layout.left && mouseX < right && mouseY >= layout.statusY - 2 && mouseY < layout.top + layout.height) {
            drawHoveringText(fontRendererObj.listFormattedStringToWidth(status, Math.min(layout.contentWidth, 320)), mouseX, mouseY, fontRendererObj);
        } else if (
            !hasWorldSource() && mouseX >= layout.contentLeft &&
                mouseX < layout.contentLeft + layout.contentWidth / 4 &&
                mouseY >= layout.actionsY &&
                mouseY < layout.actionsY + BlueprintLibraryLayout.BUTTON_HEIGHT
        ) {
            drawHoveringText(
                fontRendererObj.listFormattedStringToWidth(text("source_required"), Math.min(layout.contentWidth, 280)),
                mouseX,
                mouseY,
                fontRendererObj
            );
        } else if (hoveredRow >= 0) {
            int index = page * layout.rows + hoveredRow;
            if (index < filtered.size()) {
                BlueprintEntry entry = filtered.get(index);
                int nameWidth = layout.contentWidth - fontRendererObj.getStringWidth(dimensions(entry)) - 22;
                if (fontRendererObj.getStringWidth(entry.name) > nameWidth) {
                    drawHoveringText(
                        fontRendererObj.listFormattedStringToWidth(entry.name, Math.min(layout.contentWidth, 280)),
                        mouseX,
                        mouseY,
                        fontRendererObj
                    );
                }
            }
        }
    }

    private void drawMaterials(int mouseX, int mouseY, float partialTicks) {
        int contentRight = layout.contentLeft + layout.contentWidth;
        drawString(
            fontRendererObj,
            fontRendererObj.trimStringToWidth(text("materials_blueprint", materialsName), layout.contentWidth),
            layout.contentLeft,
            layout.searchY + 5,
            TEXT
        );
        BlueprintGuiRendering.panel(layout.contentLeft, layout.listHeaderY, contentRight, layout.listHeaderY + 14, PANEL_BACKGROUND);
        drawString(fontRendererObj, text("materials_component"), layout.contentLeft + 27, layout.listHeaderY + 3, TEXT);
        drawRightText(text("materials_quantity"), contentRight - 6, layout.listHeaderY + 3, MUTED_TEXT);
        int hoveredRow = layout.rowAt(mouseX, mouseY);
        for (int row = 0; row < layout.rows; row++) {
            int y = layout.listTop + row * BlueprintLibraryLayout.ROW_HEIGHT;
            int background = row == hoveredRow ? PANEL_BACKGROUND : (row % 2 == 0 ? 0x50222222 : 0x30000000);
            BlueprintGuiRendering.panel(layout.contentLeft, y, contentRight, y + BlueprintLibraryLayout.ROW_HEIGHT - 1, background);
            if (row >= materials.size()) continue;
            BlueprintMaterials.Entry entry = materials.get(row);
            String quantity = quantity(entry.amount);
            int nameWidth = layout.contentWidth - fontRendererObj.getStringWidth(quantity) - 42;
            ItemStack icon = row < materialIcons.size() ? materialIcons.get(row) : null;
            if (icon != null) BlueprintGuiRendering.item(itemRender, mc, icon, layout.contentLeft + 5, y + 4);
            drawString(fontRendererObj, fontRendererObj.trimStringToWidth(entry.displayName, Math.max(0, nameWidth)), layout.contentLeft + 27, y + 3, TEXT);
            drawRightText(quantity, contentRight - 6, y + 3, TEXT);
            String variant = entry.configured ? text("materials_configured") : entry.registryName + ":" + entry.metadata;
            drawString(fontRendererObj, fontRendererObj.trimStringToWidth(variant, layout.contentWidth - 34), layout.contentLeft + 27, y + 14, MUTED_TEXT);
        }
        if (materials.isEmpty()) {
            String empty = text(materialsRequest != -1 ? "materials_loading" : materialsLoaded ? "materials_empty" : "materials_unavailable");
            drawCenteredString(fontRendererObj, fontRendererObj.trimStringToWidth(empty, layout.contentWidth - 8), width / 2, layout.listTop + 8, MUTED_TEXT);
        }
        String pageLabel = materialsTotalTypes < 0 ?
            text("materials_count_pending") :
            text("materials_page", materialsPage + 1, materialsPages(), quantity(materialsTotalTypes));
        drawCenteredString(fontRendererObj, fontRendererObj.trimStringToWidth(pageLabel, layout.contentWidth - 60), width / 2, layout.pageY + 5, TEXT);
        drawString(
            fontRendererObj,
            fontRendererObj.trimStringToWidth(text("materials_notice"), layout.contentWidth),
            layout.contentLeft,
            layout.nameY + 5,
            MUTED_TEXT
        );
        String patterns = text("materials_patterns", text(materialsCopyPatterns ? "materials_on" : "materials_off"));
        drawString(fontRendererObj, fontRendererObj.trimStringToWidth(patterns, layout.contentWidth), layout.contentLeft, layout.actionsY + 5, MUTED_TEXT);
        String footer = bookmarksRequest != -1 ? text("bookmarks.loading") : materialsStatus.isEmpty() ? text("materials_hint") : materialsStatus;
        drawCenteredString(
            fontRendererObj,
            fontRendererObj.trimStringToWidth(footer, layout.contentWidth),
            width / 2,
            layout.statusY,
            materialsError ? 0xFFCC88 : MUTED_TEXT
        );
        super.drawScreen(mouseX, mouseY, partialTicks);
        if (showInfo || layout.overInfo(mouseX, mouseY)) {
            drawHoveringText(
                fontRendererObj.listFormattedStringToWidth(text("materials_help"), Math.min(layout.contentWidth, 280)),
                mouseX,
                mouseY,
                fontRendererObj
            );
        } else if (hoveredRow >= 0 && hoveredRow < materials.size()) {
            BlueprintMaterials.Entry entry = materials.get(hoveredRow);
            List<String> tooltip = new ArrayList<>();
            int tooltipWidth = Math.min(layout.contentWidth, 280);
            tooltip.addAll(fontRendererObj.listFormattedStringToWidth(entry.displayName, tooltipWidth));
            tooltip.add(text("materials_quantity_value", quantity(entry.amount)));
            tooltip.add(text(entry.configured ? "materials_configured" : "materials_unconfigured"));
            tooltip.addAll(fontRendererObj.listFormattedStringToWidth(text("materials_registry", entry.registryName, entry.metadata), tooltipWidth));
            drawHoveringText(tooltip, mouseX, mouseY, fontRendererObj);
        } else if (mouseX >= layout.contentLeft && mouseX < contentRight) {
            if (mouseY >= layout.statusY - 2 && mouseY < layout.top + layout.height && !materialsStatus.isEmpty()) {
                drawHoveringText(
                    fontRendererObj.listFormattedStringToWidth(materialsStatus, Math.min(layout.contentWidth, 320)),
                    mouseX,
                    mouseY,
                    fontRendererObj
                );
            } else if (
                mouseY >= layout.footerY && mouseY < layout.footerY + BlueprintLibraryLayout.BUTTON_HEIGHT &&
                    mouseX >= layout.buttonLeft(1, 3) &&
                    mouseX < layout.buttonLeft(1, 3) + layout.buttonWidth(1, 3)
            ) {
                String key = !BlueprintNEIBookmarks.isAvailable() ? "bookmarks.unavailable" : bookmarksAdded ? "bookmarks.already_added" : "bookmarks.help";
                drawHoveringText(fontRendererObj.listFormattedStringToWidth(text(key), Math.min(layout.contentWidth, 280)), mouseX, mouseY, fontRendererObj);
            } else if (mouseY >= layout.searchY && mouseY < layout.searchY + 18) {
                drawHoveringText(
                    fontRendererObj.listFormattedStringToWidth(materialsName, Math.min(layout.contentWidth, 280)),
                    mouseX,
                    mouseY,
                    fontRendererObj
                );
            }
        }
    }

    private static String quantity(long amount) {
        return String.format(Locale.US, "%,d", amount);
    }

    private static String dimensions(BlueprintEntry entry) {
        return text("dimensions", Math.abs((long) entry.deltas.x) + 1, Math.abs((long) entry.deltas.y) + 1, Math.abs((long) entry.deltas.z) + 1);
    }

    private void drawRightText(String value, int right, int y, int color) {
        drawString(fontRendererObj, value, right - fontRendererObj.getStringWidth(value), y, color);
    }

    private void drawField(GuiTextField field, int y, String label, float partialTicks) {
        drawString(fontRendererObj, label, layout.contentLeft, y + 5, TEXT);
        int x = layout.contentLeft + labelWidth;
        GL11.glColor4f(1, 1, 1, 1);
        TEXT_FIELD_BACKGROUND.draw(x, y, layout.contentWidth - labelWidth, 18, partialTicks);
        if (field.isFocused()) BlueprintGuiRendering.panel(x, y + 17, layout.contentLeft + layout.contentWidth, y + 18, 0xFF999999);
        field.drawTextBox();
    }

    static class LibraryButton extends GuiButton {

        LibraryButton(int id, int x, int y, int width, String label) {
            super(id, x, y, width, BlueprintLibraryLayout.BUTTON_HEIGHT, label);
        }

        @Override
        public void drawButton(Minecraft minecraft, int mouseX, int mouseY) {
            if (!visible) return;
            boolean hovered = mouseX >= xPosition && mouseX < xPosition + width && mouseY >= yPosition && mouseY < yPosition + height;
            BlueprintGuiRendering.panel(xPosition, yPosition, xPosition + width, yPosition + height, enabled && hovered ? 0xD0404040 : 0xA0000000);
            BlueprintGuiRendering.panel(xPosition, yPosition, xPosition + width, yPosition + 1, enabled ? 0x90666666 : 0x40333333);
            int color = !enabled ? 0x666666 : hovered ? 0xFFFFFF : TEXT;
            drawCenteredString(
                minecraft.fontRenderer,
                minecraft.fontRenderer.trimStringToWidth(displayString, width - 6),
                xPosition + width / 2,
                yPosition + (height - 8) / 2,
                color
            );
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    @Override
    public void onGuiClosed() {
        bookmarksRequest = -1;
        bookmarkData = null;
        Keyboard.enableRepeatEvents(false);
    }

    public static void onList(int request, List<BlueprintEntry> entries) {
        if (!(Minecraft.getMinecraft().currentScreen instanceof BlueprintLibraryScreen screen) || screen.listRequest != request) return;
        screen.listRequest = -1;
        screen.entries.clear();
        screen.entries.addAll(entries);
        if (screen.selected() == null) screen.selectedId = null;
        screen.filter();
        screen.status = "";
        screen.statusError = false;
        screen.updateButtons();
    }

    public static void onMaterials(int request, String id, int page, int totalTypes, List<BlueprintMaterials.Entry> entries) {
        if (
            !(Minecraft.getMinecraft().currentScreen instanceof BlueprintLibraryScreen screen) ||
                !screen.inMaterials() ||
                screen.materialsRequest == -1 ||
                screen.materialsRequest != request ||
                !screen.materialsId.equals(id) ||
                screen.materialsPage != page ||
                totalTypes < 0 ||
                entries == null ||
                entries.size() > screen.layout.rows
        ) return;
        screen.materialsRequest = -1;
        screen.materialsTotalTypes = totalTypes;
        if (page >= screen.materialsPages()) {
            screen.requestMaterials(screen.materialsPages() - 1);
            return;
        }
        screen.materials.clear();
        screen.materials.addAll(entries);
        screen.materialIcons.clear();
        for (BlueprintMaterials.Entry entry : entries)
            screen.materialIcons.add(BlueprintGuiRendering.icon(entry.registryName, entry.metadata));
        screen.materialsLoaded = true;
        screen.materialsStatus = "";
        screen.materialsError = false;
        screen.updateButtons();
    }

    public static void onMaterialExportData(int request, String id, int total, int offset, byte[] data) {
        if (
            !(Minecraft.getMinecraft().currentScreen instanceof BlueprintLibraryScreen screen) || !screen.inMaterials() ||
                screen.bookmarksRequest == -1 ||
                request != screen.bookmarksRequest ||
                !screen.materialsId.equals(id)
        ) return;
        int chunkSize = 16 * 1024;
        if (
            total < 1 || total > BlueprintMaterialExport.MAX_BYTES ||
                data == null ||
                offset != screen.bookmarkBytesReceived ||
                offset < 0 ||
                offset >= total ||
                offset % chunkSize != 0 ||
                data.length != Math.min(chunkSize, total - offset) ||
                (screen.bookmarkData != null && screen.bookmarkData.length != total)
        ) {
            screen.bookmarkError("mm.blueprint.bookmarks.failed", "");
            return;
        }
        if (screen.bookmarkData == null) screen.bookmarkData = new byte[total];
        System.arraycopy(data, 0, screen.bookmarkData, offset, data.length);
        screen.bookmarkBytesReceived += data.length;
        if (screen.bookmarkBytesReceived != total) return;
        byte[] complete = screen.bookmarkData;
        screen.bookmarkData = null;
        screen.bookmarksRequest = -1;
        screen.bookmarkBytesReceived = 0;
        try {
            // Only a complete validated export may mutate NEI, never individual pages/chunks.
            int added = BlueprintNEIBookmarks.add(BlueprintMaterialExport.decode(complete));
            screen.bookmarksAdded = true;
            screen.materialsStatus = text("bookmarks.success", quantity(added));
            screen.materialsError = false;
        } catch (BlueprintNEIBookmarks.ExportException e) {
            screen.bookmarkError(e.translationKey, "");
        } catch (RuntimeException e) {
            MMMod.LOG.warn("Could not decode blueprint bookmark export", e);
            screen.bookmarkError("mm.blueprint.bookmarks.failed", "");
        }
        screen.updateButtons();
    }

    public static void onError(int request, String translationKey) {
        onError(request, translationKey, "");
    }

    private static void onError(int request, String translationKey, String detail) {
        if (!(Minecraft.getMinecraft().currentScreen instanceof BlueprintLibraryScreen screen)) return;
        if (screen.bookmarksRequest != -1 && request == screen.bookmarksRequest && screen.inMaterials()) {
            screen.bookmarkError(translationKey, detail);
            return;
        }
        if (request == screen.materialsRequest && screen.inMaterials()) {
            screen.materialsRequest = -1;
            screen.materialsStatus = StatCollector.translateToLocal(translationKey);
            if (!detail.isEmpty()) {
                screen.materialsStatus += " " + detail;
                if (screen.mc.thePlayer != null) screen.mc.thePlayer.addChatMessage(new ChatComponentTranslation("mm.blueprint.error.detail", detail));
            }
            screen.materialsError = true;
            screen.updateButtons();
            return;
        }
        if (request != screen.listRequest && request != screen.operationRequest) return;
        if (request == screen.listRequest) screen.listRequest = -1;
        if (request == screen.operationRequest) {
            screen.operation = null;
            screen.operationRequest = -1;
        }
        screen.status = StatCollector.translateToLocal(translationKey);
        screen.statusError = true;
        if (!detail.isEmpty()) {
            screen.status += " " + detail;
            if (screen.mc.thePlayer != null) screen.mc.thePlayer.addChatMessage(new ChatComponentTranslation("mm.blueprint.error.detail", detail));
        }
        screen.updateButtons();
    }

    public static void onResult(int request, BlueprintStatus result, String detail) {
        if (!(Minecraft.getMinecraft().currentScreen instanceof BlueprintLibraryScreen screen)) return;
        if (result != BlueprintStatus.OK) {
            onError(request, result.translationKey(), detail);
            return;
        }
        // A materials OK is only an acknowledgement; its paged list completes the request in onMaterials.
        if (request != screen.operationRequest) return;
        BlueprintOperation operation = screen.operation;
        screen.operation = null;
        screen.operationRequest = -1;
        screen.status = StatCollector.translateToLocal(result.translationKey());
        screen.statusError = false;
        if (operation == BlueprintOperation.DELETE || operation == BlueprintOperation.RENAME) {
            BlueprintClientCache.invalidate(screen.operationId);
        }
        if (operation == BlueprintOperation.SAVE || operation == BlueprintOperation.RENAME || operation == BlueprintOperation.DELETE) {
            screen.refresh();
        }
        if (operation == BlueprintOperation.SELECT) {
            Minecraft.getMinecraft().displayGuiScreen(null);
        } else
            screen.updateButtons();
    }
}
