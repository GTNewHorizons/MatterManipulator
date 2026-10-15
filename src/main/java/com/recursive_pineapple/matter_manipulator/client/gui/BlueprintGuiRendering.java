package com.recursive_pineapple.matter_manipulator.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import cpw.mods.fml.common.registry.GameRegistry;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

import org.lwjgl.opengl.GL11;

/** Scoped rendering: neither theme hooks on vanilla Gui.drawRect nor item renderers should recolor later rows. */
@SideOnly(Side.CLIENT)
final class BlueprintGuiRendering {

    private BlueprintGuiRendering() {}

    static void panel(int left, int top, int right, int bottom, int argb) {
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_CURRENT_BIT);
        try {
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glColor4f(1, 1, 1, 1);
            Tessellator tessellator = Tessellator.instance;
            tessellator.startDrawingQuads();
            tessellator.setColorRGBA_I(argb & 0xFFFFFF, argb >>> 24);
            tessellator.addVertex(left, bottom, 0);
            tessellator.addVertex(right, bottom, 0);
            tessellator.addVertex(right, top, 0);
            tessellator.addVertex(left, top, 0);
            tessellator.draw();
        } finally {
            GL11.glPopAttrib();
        }
    }

    static ItemStack icon(String registryName, int metadata) {
        int colon = registryName.indexOf(':');
        if (colon <= 0 || colon == registryName.length() - 1) return null;
        Item item = GameRegistry.findItem(registryName.substring(0, colon), registryName.substring(colon + 1));
        return item == null ? null : new ItemStack(item, 1, metadata);
    }

    static void item(RenderItem renderer, Minecraft minecraft, ItemStack stack, int x, int y) {
        if (stack == null) return;
        GL11.glPushAttrib(
            GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_CURRENT_BIT | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_LIGHTING_BIT | GL11.GL_TEXTURE_BIT
        );
        GL11.glPushMatrix();
        float previousZ = renderer.zLevel;
        try {
            GL11.glColor4f(1, 1, 1, 1);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(true);
            GL11.glEnable(GL11.GL_ALPHA_TEST);
            RenderHelper.enableGUIStandardItemLighting();
            renderer.zLevel = 100;
            renderer.renderItemAndEffectIntoGUI(minecraft.fontRenderer, minecraft.getTextureManager(), stack, x, y);
        } finally {
            renderer.zLevel = previousZ;
            GL11.glPopMatrix();
            GL11.glPopAttrib();
        }
    }
}
