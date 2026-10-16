package com.recursive_pineapple.matter_manipulator.common.compat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import com.recursive_pineapple.matter_manipulator.common.building.InteropConstants;

import li.cil.oc.api.API;
import li.cil.oc.common.item.data.MicrocontrollerData;

/**
 * OC microcontrollers keep their parts (tier, CPU, RAM, EEPROM...) in the item's NBT instead of an inventory, so the
 * placed block can only be copied by requesting a microcontroller item with the same parts.
 * Only call this when OpenComputers is loaded.
 */
public class MicrocontrollerCompat {

    private MicrocontrollerCompat() {}

    public static boolean isMicrocontroller(ItemStack stack) {
        return stack != null && InteropConstants.OC_MICROCONTROLLER.matches(stack);
    }

    /// Copies the parts of a microcontroller, without the component addresses (the copy gets new ones).
    public static NBTTagCompound getParts(MicrocontrollerData data) {
        MicrocontrollerData copy = new MicrocontrollerData("microcontroller");
        copy.tier_$eq(data.tier());
        copy.components_$eq(
            getComponents(data).stream()
                .map(MicrocontrollerCompat::withoutAddress)
                .toArray(ItemStack[]::new)
        );

        NBTTagCompound tag = new NBTTagCompound();
        copy.save(tag);
        // Stored energy is not part of the design and would break matching with other microcontrollers
        tag.removeTag("oc:storedEnergy");
        return tag;
    }

    private static ItemStack withoutAddress(ItemStack component) {
        ItemStack copy = component.copy();

        if (
            copy.hasTagCompound() && copy.getTagCompound()
                .hasKey("oc:data")
        ) {
            NBTTagCompound data = copy.getTagCompound()
                .getCompoundTag("oc:data");

            if (data.hasKey("node")) {
                data.getCompoundTag("node")
                    .removeTag("address");
            }
        }

        return copy;
    }

    /**
     * Checks if two microcontroller items have the same design: same tier, same components and the same EEPROM code.
     * Addresses, stored energy, labels and EEPROM data are ignored, since those differ between an assembled
     * microcontroller and one that was placed.
     */
    public static boolean areEquivalent(ItemStack a, ItemStack b) {
        if (!isMicrocontroller(a) || !isMicrocontroller(b)) return false;

        MicrocontrollerData dataA = new MicrocontrollerData(a);
        MicrocontrollerData dataB = new MicrocontrollerData(b);

        if (dataA.tier() != dataB.tier()) return false;

        List<ItemStack> components = getComponents(dataA);
        List<ItemStack> unmatched = getComponents(dataB);

        if (components.size() != unmatched.size()) return false;

        outer: for (ItemStack component : components) {
            for (int i = 0; i < unmatched.size(); i++) {
                if (areComponentsEquivalent(component, unmatched.get(i))) {
                    unmatched.remove(i);
                    continue outer;
                }
            }

            return false;
        }

        return true;
    }

    /// The components without empty slots (OC adds an empty EEPROM slot when there is no EEPROM)
    private static List<ItemStack> getComponents(MicrocontrollerData data) {
        List<ItemStack> components = new ArrayList<>();

        for (ItemStack component : data.components()) {
            if (component != null) components.add(component);
        }

        return components;
    }

    private static boolean areComponentsEquivalent(ItemStack a, ItemStack b) {
        if (a.getItem() != b.getItem() || a.getItemDamage() != b.getItemDamage()) return false;

        if (API.items.get(a) != API.items.get("eeprom")) return true;

        return Arrays.equals(getEEPROMCode(a), getEEPROMCode(b));
    }

    private static byte[] getEEPROMCode(ItemStack eeprom) {
        if (!eeprom.hasTagCompound()) return new byte[0];

        return eeprom.getTagCompound()
            .getCompoundTag("oc:data")
            .getByteArray("oc:eeprom");
    }
}
