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

import com.recursive_pineapple.matter_manipulator.common.blueprint.BlueprintClientCache;
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

/** Vanilla client UI; every operation, including capture and ownership, is authorized again by the server. */
@SideOnly(Side.CLIENT)
public class BlueprintLibraryScreen extends GuiScreen {

    private static final int SAVE = 1, SELECT = 2, RENAME = 3, DELETE = 4, REFRESH = 6, DONE = 7, PREV = 8, NEXT = 9;
    private final List<BlueprintEntry> entries = new ArrayList<>();
    private final List<BlueprintEntry> filtered = new ArrayList<>();
    private GuiTextField search, name;
    private int left, panelWidth, rows, page, listTop;
    private List<String> warningLines;
    private int listRequest = -1, operationRequest = -1;
    private long listDeadline, operationDeadline;
    private String selectedId, confirmDelete, status = "";
    private String operationId;
    private BlueprintOperation operation;
    private boolean opened;

    public BlueprintLibraryScreen() {}

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        String oldSearch = search == null ? "" : search.getText();
        String oldName = name == null ? "" : name.getText();
        panelWidth = Math.min(500, width - 20);
        left = (width - panelWidth) / 2;
        warningLines = fontRendererObj.listFormattedStringToWidth(localizedWarning(), panelWidth - 8);
        listTop = 58 + warningLines.size() * fontRendererObj.FONT_HEIGHT + 4;
        rows = Math.max(1, (height - 126 - listTop) / 22);
        search = new GuiTextField(fontRendererObj, left, 33, panelWidth, 20);
        search.setMaxStringLength(64);
        search.setText(oldSearch);
        search.setFocused(true);
        name = new GuiTextField(fontRendererObj, left + 48, height - 85, panelWidth - 48, 20);
        name.setMaxStringLength(64);
        name.setText(oldName);
        buttonList.clear();
        int quarter = panelWidth / 4;
        add(SAVE, left, height - 60, quarter - 3, "save");
        add(SELECT, left + quarter, height - 60, quarter - 3, "select");
        add(RENAME, left + quarter * 2, height - 60, quarter - 3, "rename");
        add(DELETE, left + quarter * 3, height - 60, panelWidth - quarter * 3, "delete");
        int half = panelWidth / 2;
        add(REFRESH, left, height - 35, half - 3, "refresh");
        add(DONE, left + half, height - 35, panelWidth - half, "done");
        add(PREV, left, height - 123, 65, "previous");
        add(NEXT, left + panelWidth - 65, height - 123, 65, "next");
        filter();
        if (!opened) {
            opened = true;
            refresh();
        }
        updateButtons();
    }

    private void add(int id, int x, int y, int w, String key) {
        buttonList.add(new GuiButton(id, x, y, w, 20, text(key)));
    }

    private static String text(String key, Object... arguments) {
        return StatCollector.translateToLocalFormatted("mm.blueprint." + key, arguments);
    }

    private static String localizedWarning() {
        return text("warning");
    }

    private static String localized(String key, String fallback) {
        String fullKey = "mm.blueprint." + key;
        return StatCollector.canTranslate(fullKey) ? StatCollector.translateToLocal(fullKey) : fallback;
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
        return Math.max(1, (filtered.size() + rows - 1) / rows);
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
        Messages.BlueprintRequest.sendToServer(new BlueprintRequestData(BlueprintOperation.LIST, listRequest, null, null));
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
        boolean busy = operation != null;
        boolean hasSelection = selected() != null;
        boolean canSave = hasWorldSource();
        for (Object object : buttonList) {
            GuiButton button = (GuiButton) object;
            button.enabled = switch (button.id) {
                case SAVE -> !busy && canSave && validName();
                case SELECT, DELETE -> !busy && hasSelection;
                case RENAME -> !busy && hasSelection && validName();
                case REFRESH -> !busy && listRequest == -1;
                case PREV -> page > 0;
                case NEXT -> page + 1 < pages();
                default -> true;
            };
            if (button.id == DELETE) button.displayString = text(confirmDelete == null ? "delete" : "confirm");
        }
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (!button.enabled) return;
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
                }
            }
            case REFRESH -> refresh();
            case DONE -> mc.displayGuiScreen(null);
            case PREV -> page--;
            case NEXT -> page++;
            default -> {}
        }
        updateButtons();
    }

    private void send(BlueprintOperation nextOperation) {
        operation = nextOperation;
        operationId = selectedId;
        operationDeadline = System.currentTimeMillis() + 15000;
        status = text("working");
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
        if (listRequest != -1 && now > listDeadline) {
            listRequest = -1;
            status = text("timeout");
        }
        if (operation != null && now > operationDeadline) {
            operation = null;
            operationRequest = -1;
            status = text("timeout");
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
            if (confirmDelete != null) {
                confirmDelete = null;
                status = "";
                updateButtons();
            } else
                mc.displayGuiScreen(null);
            return;
        }
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
        search.mouseClicked(x, y, button);
        name.mouseClicked(x, y, button);
        if (button == 0 && x >= left && x < left + panelWidth && y >= listTop && y < listTop + rows * 22) {
            int index = page * rows + (y - listTop) / 22;
            if (index < filtered.size()) {
                BlueprintEntry entry = filtered.get(index);
                selectedId = entry.id;
                name.setText(entry.name);
                confirmDelete = null;
                status = "";
                updateButtons();
            }
        }
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0) page = Math.max(0, Math.min(pages() - 1, page + (wheel < 0 ? 1 : -1)));
        updateButtons();
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        drawCenteredString(fontRendererObj, text("title"), width / 2, 9, 0xFFFFFF);
        drawString(fontRendererObj, text("search"), left, 22, 0xBBBBBB);
        search.drawTextBox();
        for (int i = 0; i < warningLines.size(); i++) {
            drawString(fontRendererObj, warningLines.get(i), left + 4, 58 + i * fontRendererObj.FONT_HEIGHT, 0xFFCC88);
        }
        for (int row = 0; row < rows; row++) {
            int index = page * rows + row;
            int y = listTop + row * 22;
            drawRect(left, y, left + panelWidth, y + 21, 0x80303030);
            if (index >= filtered.size()) continue;
            BlueprintEntry entry = filtered.get(index);
            if (entry.id.equals(selectedId)) drawRect(left, y, left + panelWidth, y + 21, 0xA0506070);
            drawString(fontRendererObj, fontRendererObj.trimStringToWidth(entry.name, panelWidth - 8), left + 4, y + 3, 0xFFFFFF);
            drawString(
                fontRendererObj,
                text(
                    "dimensions",
                    Math.abs((long) entry.deltas.x) + 1,
                    Math.abs((long) entry.deltas.y) + 1,
                    Math.abs((long) entry.deltas.z) + 1
                ),
                left + 4,
                y + 12,
                0xBBBBBB
            );
        }
        if (filtered.isEmpty()) drawCenteredString(fontRendererObj, text("empty"), width / 2, listTop + 7, 0xBBBBBB);
        drawCenteredString(fontRendererObj, text("page", page + 1, pages(), filtered.size()), width / 2, height - 117, 0xFFFFFF);
        BlueprintEntry entry = selected();
        if (entry != null) {
            drawString(fontRendererObj, text("blocks", entry.blockCount), left, height - 98, 0xBBBBBB);
        }
        drawString(fontRendererObj, text("name"), left, height - 79, 0xFFFFFF);
        name.drawTextBox();
        drawCenteredString(fontRendererObj, fontRendererObj.trimStringToWidth(status, panelWidth), width / 2, height - 11, 0xFFFF88);
        super.drawScreen(mouseX, mouseY, partialTicks);
        if (!status.isEmpty() && mouseY >= height - 13) {
            drawHoveringText(fontRendererObj.listFormattedStringToWidth(status, Math.min(panelWidth, 320)), mouseX, mouseY, fontRendererObj);
        }
        if (!hasWorldSource() && mouseX >= left && mouseX < left + panelWidth / 4 && mouseY >= height - 60 && mouseY < height - 40) {
            String guidance = localized("source_required", "To save, use Mark Copy in Copy mode and mark both source corners.");
            drawHoveringText(fontRendererObj.listFormattedStringToWidth(guidance, Math.min(panelWidth, 280)), mouseX, mouseY, fontRendererObj);
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    @Override
    public void onGuiClosed() {
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
        screen.updateButtons();
    }

    public static void onError(int request, String translationKey) {
        onError(request, translationKey, "");
    }

    private static void onError(int request, String translationKey, String detail) {
        if (!(Minecraft.getMinecraft().currentScreen instanceof BlueprintLibraryScreen screen)) return;
        if (request != screen.listRequest && request != screen.operationRequest) return;
        if (request == screen.listRequest) screen.listRequest = -1;
        if (request == screen.operationRequest) {
            screen.operation = null;
            screen.operationRequest = -1;
        }
        screen.status = StatCollector.translateToLocal(translationKey);
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
        if (request != screen.operationRequest) return;
        BlueprintOperation operation = screen.operation;
        screen.operation = null;
        screen.operationRequest = -1;
        screen.status = StatCollector.translateToLocal(result.translationKey());
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
