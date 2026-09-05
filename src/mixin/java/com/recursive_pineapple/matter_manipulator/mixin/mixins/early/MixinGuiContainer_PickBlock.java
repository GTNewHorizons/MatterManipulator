package com.recursive_pineapple.matter_manipulator.mixin.mixins.early;

import com.recursive_pineapple.matter_manipulator.common.items.manipulator.ItemMatterManipulator;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.MMState;
import com.recursive_pineapple.matter_manipulator.common.networking.Messages;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.relauncher.ReflectionHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import org.lwjgl.input.Keyboard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

@Mixin(GuiContainer.class)
public abstract class MixinGuiContainer_PickBlock {

    private static final Field theSlot = ReflectionHelper
        .findField(GuiContainer.class, "theSlot", "field_147006_u", "u");
    private static boolean neiLoaded = false;
    private static Method getStackMouseOver = null;
    private static Method isNEIHidden = null;

    static {
        try {
            neiLoaded = Loader.isModLoaded("NotEnoughItems");
            if (neiLoaded) {
                isNEIHidden = Class.forName("codechicken.nei.NEIClientConfig").getDeclaredMethod("isHidden");
                getStackMouseOver = Class.forName("codechicken.nei.guihook.GuiContainerManager")
                    .getDeclaredMethod("getStackMouseOver", GuiContainer.class);
            }
        } catch (Exception ignored) {}
    }

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
            Slot hoveredSlot = (Slot) theSlot.get((GuiContainer) (Object) this);

            // get the stack
            if (hoveredSlot != null) {
                hoveredStack = hoveredSlot.getStack();
            }

            // try NEI
            if (hoveredStack == null && isNEIHidden != null && !(boolean) isNEIHidden.invoke(null) && getStackMouseOver != null) {
                hoveredStack = (ItemStack) (getStackMouseOver.invoke(null, (GuiContainer) (Object) this));
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            e.printStackTrace();
        }
        // I hate it, but haven't found another way to make this work with sneak key.
        final boolean isSneak = Keyboard.isKeyDown(Keyboard.KEY_LSHIFT) ;

        // call onMMBPressed on the client and the server
        MMState state = ItemMatterManipulator.getState(cursorStack);
        ItemMatterManipulator.onMMBPressedInGUI(player, cursorStack, state, isSneak, hoveredStack);
        ItemMatterManipulator.setState(cursorStack, state);

        Messages.MMBPressedInGUI.sendToServer(new Messages.CursorItemStackData(hoveredStack, isSneak));
    }
}
