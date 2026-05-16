package com.recursive_pineapple.matter_manipulator.client.gui;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiTextField;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * A text field that only accepts integers within a given [min, max] range.
 */
@SideOnly(Side.CLIENT)
class IntTextField extends GuiTextField {

    private final int min, max;

    IntTextField(FontRenderer fontRenderer, int x, int y, int w, int h, int min, int max) {
        super(fontRenderer, x, y, w, h);
        this.min = min;
        this.max = max;
    }

    @Override
    public boolean textboxKeyTyped(char typedChar, int keyCode) {
        if (typedChar >= 32 && typedChar != '-' && !Character.isDigit(typedChar)) { return false; }
        String before = getText();
        boolean result = super.textboxKeyTyped(typedChar, keyCode);
        String text = getText();
        if (text.isEmpty() || text.equals("-")) { return result; }
        try {
            int val = Integer.parseInt(text);
            if (val < min || val > max) {
                setText(before);
                setCursorPositionEnd();
                return false;
            }
        } catch (NumberFormatException e) {
            setText(before);
            setCursorPositionEnd();
            return false;
        }
        return result;
    }
}
