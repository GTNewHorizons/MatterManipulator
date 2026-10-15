package com.recursive_pineapple.matter_manipulator.common.blueprint;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagByteArray;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagIntArray;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.world.World;

import cpw.mods.fml.common.registry.GameRegistry;
import cpw.mods.fml.common.registry.GameRegistry.UniqueIdentifier;

import com.recursive_pineapple.matter_manipulator.common.building.BlockAnalyzer;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.ItemMatterManipulator;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.MMConfig;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.MMState;

/** Read-only requirements for one raw snapshot, independent of the active placement plan. */
public final class BlueprintMaterials {

    private BlueprintMaterials() {}

    /**
     * The caller must authenticate library ownership before supplying the blueprint; snapshots have no owner field.
     * No inventory, energy, network connection or destination blocks are consulted.
     */
    public static List<Entry> analyze(EntityPlayer player, MMState state, StructureBlueprint blueprint) {
        List<Material> materials = calculate(player, state, blueprint);
        List<Entry> entries = new ArrayList<>(materials.size());
        for (Material material : materials)
            entries.add(material.entry);
        return Collections.unmodifiableList(entries);
    }

    /** All requirements for one copy, with exact configuration and no aliases into the blueprint or analysis. */
    public static List<StackEntry> export(EntityPlayer player, MMState state, StructureBlueprint blueprint) {
        List<Material> materials = calculate(player, state, blueprint);
        List<StackEntry> entries = new ArrayList<>(materials.size());
        for (Material material : materials)
            entries.add(material.stackEntry);
        return Collections.unmodifiableList(entries);
    }

    private static List<Material> calculate(EntityPlayer player, MMState state, StructureBlueprint blueprint) {
        if (!state.hasCap(ItemMatterManipulator.ALLOW_COPYING)) { throw new IllegalArgumentException("This manipulator cannot copy blueprints"); }

        World world = player.getEntityWorld();
        MMState workingState = new MMState();
        workingState.config = new MMConfig();
        workingState.config.shouldCopyAEPatterns = state.config.shouldCopyAEPatterns;

        var analysis = BlockAnalyzer.getRequiredItemsForBuild(player, workingState, blueprint.instantiate(world).blocks, true);
        List<Material> materials = new ArrayList<>();
        for (var required : analysis.requiredItems.entrySet()) {
            long amount = required.getValue();
            if (amount <= 0) continue;

            ItemStack stack = required.getKey().getItemStack();
            UniqueIdentifier id = GameRegistry.findUniqueIdentifierFor(stack.getItem());
            if (id == null) throw new IllegalArgumentException("Unregistered blueprint material");
            Entry entry = new Entry(id.toString(), Items.feather.getDamage(stack), stack.getDisplayName(), amount, stack.hasTagCompound());
            materials.add(new Material(entry, new StackEntry(stack, amount), configurationKey(stack.getTagCompound())));
        }
        materials.sort(
            Comparator.comparing((Material material) -> material.entry.displayName)
                .thenComparing(material -> material.entry.registryName)
                .thenComparingInt(material -> material.entry.metadata)
                .thenComparing(material -> material.configuration)
        );
        return materials;
    }

    /** A detached stack identity (count one); the requirement is always stored as a long. */
    public static final class StackEntry {

        public final ItemStack stack;
        public final long amount;

        public StackEntry(ItemStack stack, long amount) {
            if (stack == null || stack.getItem() == null) throw new IllegalArgumentException("Missing blueprint material item");
            if (amount <= 0) throw new IllegalArgumentException("Invalid blueprint material amount");
            this.stack = stack.copy();
            this.stack.stackSize = 1;
            this.amount = amount;
        }
    }

    /** Wire-safe presentation data; configured variants remain separate rows without exposing their NBT. */
    public static final class Entry {

        public final String registryName, displayName;
        public final int metadata;
        public final long amount;
        public final boolean configured;

        public Entry(String registryName, int metadata, String displayName, long amount, boolean configured) {
            this.registryName = Objects.requireNonNull(registryName, "registryName");
            if (registryName.getBytes(StandardCharsets.UTF_8).length > 256) {
                throw new IllegalArgumentException("Material registry name exceeds 256 UTF-8 bytes");
            }
            this.metadata = metadata;
            this.displayName = safeDisplayName(Objects.requireNonNull(displayName, "displayName"));
            this.amount = amount;
            this.configured = configured;
        }
    }

    private static String safeDisplayName(String name) {
        name = EnumChatFormatting.getTextWithoutFormattingCodes(name);
        StringBuilder result = new StringBuilder();
        int count = 0;
        for (int offset = 0; offset < name.length() && count < 128;) {
            int codePoint = name.codePointAt(offset);
            offset += Character.charCount(codePoint);
            int type = Character.getType(codePoint);
            if (Character.isISOControl(codePoint) || type == Character.FORMAT || type == Character.SURROGATE || codePoint == '\u00a7') {
                continue;
            }
            result.appendCodePoint(codePoint);
            count++;
        }
        // At most 128 valid Unicode code points also guarantees at most 512 UTF-8 bytes.
        return result.toString();
    }

    private static final class Material {

        private final Entry entry;
        private final StackEntry stackEntry;
        private final String configuration;

        private Material(Entry entry, StackEntry stackEntry, String configuration) {
            this.entry = entry;
            this.stackEntry = stackEntry;
            this.configuration = configuration;
        }
    }

    /** Compound iteration order is unspecified, and array toString() omits contents in vanilla NBT. */
    private static String configurationKey(NBTBase tag) {
        if (tag == null) return "";
        StringBuilder key = new StringBuilder().append(tag.getId()).append(':');
        if (tag instanceof NBTTagCompound compound) {
            for (String name : new TreeSet<>(compound.func_150296_c())) {
                key.append(name.length()).append(':').append(name);
                String value = configurationKey(compound.getTag(name));
                key.append(value.length()).append(':').append(value);
            }
        } else if (tag instanceof NBTTagList list) {
            for (int i = 0; i < list.tagCount(); i++) {
                String value = configurationKey((NBTBase) list.tagList.get(i));
                key.append(value.length()).append(':').append(value);
            }
        } else if (tag instanceof NBTTagByteArray bytes) {
            key.append(Arrays.toString(bytes.func_150292_c()));
        } else if (tag instanceof NBTTagIntArray ints) {
            key.append(Arrays.toString(ints.func_150302_c()));
        } else {
            key.append(tag);
        }
        return key.toString();
    }
}
