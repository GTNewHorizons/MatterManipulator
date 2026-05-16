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

    private static final int PANEL_W    = 300;
    private static final int PANEL_H    = 220;
    private static final int LIST_TOP   = 22;
    private static final int ENTRY_H    = 20;
    private static final int MAX_VIS    = 6;
    private static final int LIST_BOT   = LIST_TOP + MAX_VIS * ENTRY_H;  // 142

    // Button ID ranges
    private static final int ID_LOAD  = 100;  // 100..105
    private static final int ID_DEL   = 200;  // 200..205
    private static final int ID_SAVE  = 300;
    private static final int ID_CLOSE = 301;

    private final List<String> names = new ArrayList<>();
    private int scroll = 0;
    private GuiTextField nameField;

    public GuiSavedConfigs(Map<String, MMConfig> savedConfigs) {
        if (savedConfigs != null) {
            names.addAll(savedConfigs.keySet());
        }
    }

    private int px() { return (width  - PANEL_W) / 2; }
    private int py() { return (height - PANEL_H) / 2; }

    @Override
    @SuppressWarnings("unchecked")
    public void initGui() {
        buttonList.clear();
        int px = px(), py = py();

        for (int i = 0; i < MAX_VIS; i++) {
            int ry = py + LIST_TOP + i * ENTRY_H;
            buttonList.add(new GuiButton(ID_LOAD + i, px + 200, ry + 2, 44, 16,
                StatCollector.translateToLocal("mm.gui.saved_configs.load")));
            buttonList.add(new GuiButton(ID_DEL + i, px + 248, ry + 2, 44, 16,
                StatCollector.translateToLocal("mm.gui.saved_configs.delete")));
        }

        buttonList.add(new GuiButton(ID_SAVE, px + 8, py + LIST_BOT + 38, 80, 18,
            StatCollector.translateToLocal("mm.gui.saved_configs.save")));
        buttonList.add(new GuiButton(ID_CLOSE, px + PANEL_W - 88, py + LIST_BOT + 58, 80, 18,
            StatCollector.translateToLocal("mm.gui.saved_configs.close")));

        nameField = new GuiTextField(fontRendererObj, px + 8, py + LIST_BOT + 16, PANEL_W - 16, 16);
        nameField.setMaxStringLength(64);
        nameField.setFocused(true);

        syncButtons();
    }

    private void syncButtons() {
        for (int i = 0; i < MAX_VIS; i++) {
            boolean exists = (i + scroll) < names.size();
            findButton(ID_LOAD + i).enabled = exists;
            findButton(ID_DEL  + i).enabled = exists;
        }
    }

    @SuppressWarnings("unchecked")
    private GuiButton findButton(int id) {
        for (Object o : buttonList) {
            GuiButton b = (GuiButton) o;
            if (b.id == id) return b;
        }
        throw new IllegalStateException("Button " + id + " not found");
    }

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
                if (!names.contains(name)) names.add(name);
                Messages.SaveConfig.sendToServer(name);
                nameField.setText("");
                syncButtons();
            }
        } else if (id == ID_CLOSE) {
            mc.displayGuiScreen(null);
        }
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
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();

        int px = px(), py = py();

        // Panel border + background
        drawRect(px - 1, py - 1, px + PANEL_W + 1, py + PANEL_H + 1, 0xFF555555);
        drawRect(px,     py,     px + PANEL_W,     py + PANEL_H,     0xFF1E1E1E);

        // Title
        drawCenteredString(fontRendererObj,
            "§e" + StatCollector.translateToLocal("mm.gui.saved_configs.title"),
            px + PANEL_W / 2, py + 7, 0xFFFFFF);
        drawRect(px + 8, py + 17, px + PANEL_W - 8, py + 18, 0xFF666666);

        // List rows
        for (int i = 0; i < MAX_VIS; i++) {
            int idx = i + scroll;
            int ry  = py + LIST_TOP + i * ENTRY_H;

            if ((i % 2) == 0) drawRect(px + 4, ry + 1, px + 196, ry + ENTRY_H - 1, 0x18FFFFFF);

            if (idx < names.size()) {
                String label = fontRendererObj.trimStringToWidth(names.get(idx), 183);
                fontRendererObj.drawString(label, px + 8, ry + 6, 0xFFFFFF);
            } else if (names.isEmpty() && i == 0) {
                fontRendererObj.drawString(
                    StatCollector.translateToLocal("mm.gui.saved_configs.empty"),
                    px + 8, ry + 6, 0xFF777777);
            }
        }

        // Scrollbar (only when list overflows)
        if (names.size() > MAX_VIS) {
            int trackH = LIST_BOT - LIST_TOP;
            drawRect(px + 193, py + LIST_TOP, px + 197, py + LIST_BOT, 0x44FFFFFF);
            int thumbH = Math.max(4, trackH * MAX_VIS / names.size());
            int thumbY = (trackH - thumbH) * scroll / (names.size() - MAX_VIS);
            drawRect(px + 193, py + LIST_TOP + thumbY,
                     px + 197, py + LIST_TOP + thumbY + thumbH, 0xAAFFFFFF);
        }

        // Footer divider
        drawRect(px + 8, py + LIST_BOT + 2, px + PANEL_W - 8, py + LIST_BOT + 3, 0xFF666666);

        // Config name label + field
        fontRendererObj.drawString(
            StatCollector.translateToLocal("mm.gui.saved_configs.name_label"),
            px + 8, py + LIST_BOT + 6, 0xAAAAAA);
        nameField.drawTextBox();

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    protected void keyTyped(char ch, int keyCode) {
        if (nameField.textboxKeyTyped(ch, keyCode)) return;
        if (keyCode == 28 || keyCode == 156) {  // Enter / numpad Enter
            actionPerformed(findButton(ID_SAVE));
        } else {
            super.keyTyped(ch, keyCode);
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        nameField.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    public boolean doesGuiPauseGame() { return false; }
}
