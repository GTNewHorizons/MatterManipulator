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

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import it.unimi.dsi.fastutil.booleans.BooleanObjectImmutablePair;
import li.cil.oc.api.API;

public class ComputerComponentItemProvider implements IItemProvider {

    /// Components where any matching tier/type is fine, even with an assigned address.
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

    public static final ItemStack EEPROM = API.items.get("eeprom").createItemStack(1);
    public static final ItemStack HDD_1 = API.items.get("hdd1").createItemStack(1);
    public static final ItemStack HDD_2 = API.items.get("hdd2").createItemStack(1);
    public static final ItemStack HDD_3 = API.items.get("hdd3").createItemStack(1);
    public static final ItemStack FLOPPY = API.items.get("floppy").createItemStack(1);

    public @NotNull ItemStack component;

    public ComputerComponentItemProvider(@NotNull ItemStack component) {
        this.component = component;
    }

    public static ComputerComponentItemProvider fromStack(ItemStack stack) {
        if (stack == null) return null;
        if (API.items.get(stack) == null) return null;
        return new ComputerComponentItemProvider(stack);
    }

    @Override
    public @Nullable ItemStack getStack(IPseudoInventory inv, boolean consume) {
        if (!consume) return component;

        // Addressed components are consumed fuzzily, then recreated without copying their NBT.
        if (FUZZY_COMPONENT_DAMAGE.contains(component.getItemDamage())) {
            BooleanObjectImmutablePair<List<BigItemStack>> result = inv
                .tryConsumeItems(Collections.singletonList(BigItemStack.create(component)), IPseudoInventory.CONSUME_FUZZY);
            if (!result.leftBoolean()) return null;
            return API.items.get(component).createItemStack(1);
        }

        if (component.getItemDamage() == EEPROM.getItemDamage()) {
            ItemStack copiedEEPROM = API.items.get(component).createItemStack(1);

            // Copy the programmed EEPROM data, but force OC to assign a fresh address.
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

            // Prefer an exact matching EEPROM; otherwise program an empty one.
            return inv.tryConsumeItems(copiedEEPROM) || inv.tryConsumeItems(EEPROM) ? copiedEEPROM : null;
        }

        // HDDs and floppies are consumed empty; their data is not copied.
        if (component.getItemDamage() == HDD_1.getItemDamage()) return inv.tryConsumeItems(HDD_1) ? HDD_1.copy() : null;
        if (component.getItemDamage() == HDD_2.getItemDamage()) return inv.tryConsumeItems(HDD_2) ? HDD_2.copy() : null;
        if (component.getItemDamage() == HDD_3.getItemDamage()) return inv.tryConsumeItems(HDD_3) ? HDD_3.copy() : null;
        if (component.getItemDamage() == FLOPPY.getItemDamage()) return inv.tryConsumeItems(FLOPPY) ? FLOPPY.copy() : null;

        return null;
    }

    @Override
    public IItemProvider clone() {
        return new ComputerComponentItemProvider(component);
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof ComputerComponentItemProvider provider)) return false;

        // Most OC components are equal by descriptor; assigned addresses do not matter.
        if (component.getItemDamage() != EEPROM.getItemDamage()) return API.items.get(component).equals(API.items.get(provider.component));

        if (provider.component.getItemDamage() != EEPROM.getItemDamage()) return false;

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

        // EEPROMs must match exactly, except for their assigned OC address.
        nodeThis.removeTag("address");
        nodeOther.removeTag("address");

        return tagThis.equals(tagOther);
    }
}
