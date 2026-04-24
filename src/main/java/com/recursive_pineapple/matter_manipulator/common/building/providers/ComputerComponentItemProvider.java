package com.recursive_pineapple.matter_manipulator.common.building.providers;

import java.util.Collections;
import java.util.List;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import com.recursive_pineapple.matter_manipulator.common.building.IPseudoInventory;
import com.recursive_pineapple.matter_manipulator.common.utils.BigItemStack;

import org.jetbrains.annotations.Nullable;

import it.unimi.dsi.fastutil.booleans.BooleanObjectImmutablePair;
import li.cil.oc.api.API;
import li.cil.oc.api.detail.ItemInfo;

public class ComputerComponentItemProvider implements IItemProvider {

    public ItemStack component;

    public ComputerComponentItemProvider() {}

    public static ComputerComponentItemProvider fromStack(ItemStack stack) {
        if (stack == null) return null;

        ItemInfo component = API.items.get(stack);

        if (component == null) return null;

        ComputerComponentItemProvider provider = new ComputerComponentItemProvider();

        provider.component = stack;

        return provider;
    }

    @Override
    public @Nullable ItemStack getStack(IPseudoInventory inv, boolean consume) {
        if (!consume) return component;

        // any cpu, gpu, or ram can be used, even if it has assigned uuid
        if (
            component.getItemDamage() == API.items.get("cpu1").createItemStack(1).getItemDamage() ||
                component.getItemDamage() == API.items.get("cpu2").createItemStack(1).getItemDamage() ||
                component.getItemDamage() == API.items.get("cpu3").createItemStack(1).getItemDamage() ||
                component.getItemDamage() == API.items.get("graphicsCard1").createItemStack(1).getItemDamage() ||
                component.getItemDamage() == API.items.get("graphicsCard2").createItemStack(1).getItemDamage() ||
                component.getItemDamage() == API.items.get("graphicsCard3").createItemStack(1).getItemDamage() ||
                component.getItemDamage() == API.items.get("ram1").createItemStack(1).getItemDamage() ||
                component.getItemDamage() == API.items.get("ram2").createItemStack(1).getItemDamage() ||
                component.getItemDamage() == API.items.get("ram3").createItemStack(1).getItemDamage() ||
                component.getItemDamage() == API.items.get("ram4").createItemStack(1).getItemDamage() ||
                component.getItemDamage() == API.items.get("ram5").createItemStack(1).getItemDamage() ||
                component.getItemDamage() == API.items.get("ram6").createItemStack(1).getItemDamage()
        ) {
            BooleanObjectImmutablePair<List<BigItemStack>> result = inv
                .tryConsumeItems(Collections.singletonList(BigItemStack.create(component)), IPseudoInventory.CONSUME_FUZZY);
            if (!result.leftBoolean()) return null;
            return API.items.get(component).createItemStack(1);
        }

        // take a matching eeprom, or program an empty one
        if (component.getItemDamage() == API.items.get("eeprom").createItemStack(1).getItemDamage()) {
            ItemStack copiedEEPROM = API.items.get(component).createItemStack(1);

            // copy all NBT from the source component, but strip the address
            if (component.hasTagCompound()) {
                NBTTagCompound tag = (NBTTagCompound) component.getTagCompound().copy();
                if (tag.hasKey("oc:data")) {
                    NBTTagCompound ocData = tag.getCompoundTag("oc:data");
                    if (ocData.hasKey("node")) {
                        NBTTagCompound node = ocData.getCompoundTag("node");
                        node.removeTag("address");
                        ocData.setTag("node", node);
                    }
                    tag.setTag("oc:data", ocData);
                }
                copiedEEPROM.setTagCompound(tag);
            }

            if (inv.tryConsumeItems(copiedEEPROM) || inv.tryConsumeItems(API.items.get("eeprom").createItemStack(1))) return copiedEEPROM;
            return null;
        }

        return null;
    }

    @Override
    public IItemProvider clone() {
        ComputerComponentItemProvider provider = new ComputerComponentItemProvider();
        provider.component = component;
        return provider;
    }
}
