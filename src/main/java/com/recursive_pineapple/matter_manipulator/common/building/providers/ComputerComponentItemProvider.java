package com.recursive_pineapple.matter_manipulator.common.building.providers;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import com.recursive_pineapple.matter_manipulator.common.building.IPseudoInventory;
import com.recursive_pineapple.matter_manipulator.common.utils.BigItemStack;

import org.jetbrains.annotations.Nullable;

import it.unimi.dsi.fastutil.booleans.BooleanObjectImmutablePair;
import li.cil.oc.api.API;
import li.cil.oc.api.detail.ItemInfo;

public class ComputerComponentItemProvider implements IItemProvider {

    private static final HashSet<Integer> FUZZY_COMPONENT_DAMAGE = Arrays.stream(new String[] {
        // spotless:off
        "cpu1",
        "cpu2",
        "cpu3",
        "dataCard1",
        "dataCard2",
        "dataCard3",
        "internetCard",
        "lanCard",
        "wlanCard1",
        "wlanCard2",
        "linkedCard",
        "redstoneCard1",
        "redstoneCard2",
        "tpsCard",
        "debugCard",
        "graphicsCard1",
        "graphicsCard2",
        "graphicsCard3",
        "ram1",
        "ram2",
        "ram3",
        "ram4",
        "ram5",
        "ram6"
        // spotless:on
    })
        .map(name -> API.items.get(name).createItemStack(1).getItemDamage())
        .collect(Collectors.toCollection(HashSet::new));

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
        if (FUZZY_COMPONENT_DAMAGE.contains(component.getItemDamage())) {
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
                    NBTTagCompound data = tag.getCompoundTag("oc:data");
                    if (data.hasKey("node")) {
                        NBTTagCompound node = data.getCompoundTag("node");
                        node.removeTag("address");
                    }
                }
                copiedEEPROM.setTagCompound(tag);
            }

            if (inv.tryConsumeItems(copiedEEPROM) || inv.tryConsumeItems(API.items.get("eeprom").createItemStack(1))) return copiedEEPROM;
            return null;
        }

        // take empty hdds, do not program them
        // TODO: override equality so disks are equal whenever their tier is equal, regardless of content
        if (component.getItemDamage() == API.items.get("hdd1").createItemStack(1).getItemDamage()) {
            if (inv.tryConsumeItems(API.items.get("hdd1").createItemStack(1))) return API.items.get("hdd1").createItemStack(1);
            return null;
        }

        if (component.getItemDamage() == API.items.get("hdd2").createItemStack(1).getItemDamage()) {
            if (inv.tryConsumeItems(API.items.get("hdd2").createItemStack(1))) return API.items.get("hdd2").createItemStack(1);
            return null;
        }

        if (component.getItemDamage() == API.items.get("hdd3").createItemStack(1).getItemDamage()) {
            if (inv.tryConsumeItems(API.items.get("hdd3").createItemStack(1))) return API.items.get("hdd3").createItemStack(1);
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

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof ComputerComponentItemProvider provider)) return false;
        if (component.getItemDamage() != API.items.get("eeprom").createItemStack(1).getItemDamage()) return component.equals(provider.component);
        if (provider.component.getItemDamage() != API.items.get("eeprom").createItemStack(1).getItemDamage()) return false;

        NBTTagCompound tagThis = (NBTTagCompound) component.getTagCompound().copy();
        NBTTagCompound tagOther = (NBTTagCompound) provider.component.getTagCompound().copy();

        if (!tagThis.hasKey("oc:data")) return false;
        if (!tagOther.hasKey("oc:data")) return false;

        NBTTagCompound dataThis = tagThis.getCompoundTag("oc:data");
        NBTTagCompound dataOther = tagOther.getCompoundTag("oc:data");

        if (!dataThis.hasKey("node")) return false;
        if (!dataOther.hasKey("node")) return false;

        NBTTagCompound nodeThis = dataThis.getCompoundTag("node");
        NBTTagCompound nodeOther = dataOther.getCompoundTag("node");

        nodeThis.removeTag("address");
        nodeOther.removeTag("address");

        return tagThis.equals(tagOther);
    }
}
