package com.recursive_pineapple.matter_manipulator.client.gui;

import net.minecraft.client.gui.GuiTextField;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * Holds the live text-field widgets for a single condition row.
 */
@SideOnly(Side.CLIENT)
class CondRowUI {

    GuiTextField blockField;
    GuiTextField[] atFields; // [dx, dy, dz] when in "at X Y Z" mode; null otherwise
    int fieldScreenY; // used for viewport visibility culling
}
