package com.recursive_pineapple.matter_manipulator.mixin.mixins.early;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import org.lwjgl.input.Keyboard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import codechicken.nei.NEIClientConfig;
import codechicken.nei.guihook.GuiContainerManager;

import com.recursive_pineapple.matter_manipulator.MMMod;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.ItemMatterManipulator;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.MMState;
import com.recursive_pineapple.matter_manipulator.common.networking.Messages;
import com.recursive_pineapple.matter_manipulator.common.utils.Mods;

@Mixin(GuiContainer.class)
public abstract class MixinGuiContainer_PickBlock {

    // Middle Click on an item with the MM picked up switches to that block.
    @Inject(method = "mouseClicked(III)V", at = @At("HEAD"), cancellable = true)
    private void mm$onMouseClicked(int mouseX, int mouseY, int mouseButton, CallbackInfo ci) {
        if (mouseButton != 2) {
            return;
        }

        EntityPlayer player = Minecraft.getMinecraft().thePlayer;
        if (player == null) {
            return;
        }

        ItemStack cursorStack = player.inventory.getItemStack();
        if (cursorStack == null || !(cursorStack.getItem() instanceof ItemMatterManipulator)) {
            return;
        }

        ci.cancel();

        ItemStack hoveredStack = null;
        // get the hovered stack from the active container
        try {
            // try regular container
            Slot hoveredSlot = mm$invokeGetSlotAtPosition(mouseX, mouseY);

            // get the stack
            if (hoveredSlot != null) {
                hoveredStack = hoveredSlot.getStack();
            }

            // try NEI
            if (hoveredStack == null && Mods.NotEnoughItems.isModLoaded() && !NEIClientConfig.isHidden()) {
                hoveredStack = GuiContainerManager.getStackMouseOver((GuiContainer) (Object) this);
            }
        } catch (Exception e) {
            MMMod.LOG.error("Error while handling middle click in GUI", e);
        }
        final boolean isSneak = Keyboard.isKeyDown(Minecraft.getMinecraft().gameSettings.keyBindSneak.getKeyCode());

        // call onMMBPressed on the client and the server
        MMState state = ItemMatterManipulator.getState(cursorStack);
        ItemMatterManipulator.onMMBPressedInGUI(player, cursorStack, state, isSneak, hoveredStack);
        ItemMatterManipulator.setState(cursorStack, state);

        Messages.MMBPressedInGUI.sendToServer(new Messages.CursorItemStackData(hoveredStack, isSneak));
    }

    @Invoker("getSlotAtPosition")
    protected abstract Slot mm$invokeGetSlotAtPosition(int x, int y);
}
