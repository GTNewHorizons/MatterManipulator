package com.recursive_pineapple.matter_manipulator.common.building;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.IChatComponent;
import net.minecraft.world.World;

import cpw.mods.fml.common.registry.GameRegistry.UniqueIdentifier;

import com.gtnewhorizon.gtnhlib.util.data.ImmutableBlockMeta;
import com.gtnewhorizon.gtnhlib.util.data.ImmutableItemMeta;

import org.jetbrains.annotations.NotNull;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;

public interface ImmutableBlockSpec extends ImmutableItemMeta, ImmutableBlockMeta {

    UniqueIdentifier getObjectId();

    @NotNull
    Block getBlock();

    @NotNull
    Item getItem();

    PendingBlock instantiate(int worldId, int x, int y, int z);

    default PendingBlock instantiate(World world, int x, int y, int z) {
        return instantiate(world.provider.dimensionId, x, y, z);
    }

    String getProperty(CopyableProperty property);

    ImmutableBlockSpec withProperties(Map<CopyableProperty, String> properties);

    default boolean isEquivalent(ImmutableBlockSpec other) {
        return ItemStack.areItemStacksEqual(toStack(1), other.toStack(1));
    }

    /** Returns true when this contains air. BlockSpecs may be air if an invalid block was analyzed. */
    default boolean isAir() {
        return getObjectId() == null || getBlock() == null || InteropConstants.isAir(getBlock(), getBlockMeta());
    }

    default boolean skipWhenCopying() {
        return InteropConstants.skipWhenCopying(getBlock(), getBlockMeta());
    }

    default boolean shouldDropItem() {
        return InteropConstants.shouldDropItem(getBlock(), getBlockMeta());
    }

    default boolean isFree() {
        return InteropConstants.isFree(getBlock(), getBlockMeta());
    }

    void getItemDetailsChat(List<IChatComponent> details);

    static Comparator<ImmutableBlockSpec> getComparator() {
        return Comparator.comparing(s -> s.toStack(1), (a, b) -> {
            if (a == null && b != null) return -1;
            if (a != null && b == null) return 1;
            if (a == null && b == null) return 0;

            assert a.getItem() != null;
            assert b.getItem() != null;

            int result;

            result = String.CASE_INSENSITIVE_ORDER.compare(a.getItem().delegate.name(), b.getItem().delegate.name());
            if (result != 0) return result;

            result = Integer.compare(a.itemDamage, b.itemDamage);
            if (result != 0) return result;

            NBTTagCompound ta = a.getTagCompound();
            NBTTagCompound tb = b.getTagCompound();

            if (ta == null && tb != null) return -1;
            if (ta != null && tb == null) return 1;
            if (ta == null && tb == null) return 0;

            return Integer.compare(ta.hashCode(), tb.hashCode());
        });
    }

    static BlockSpec fromBlockMeta(ImmutableBlockMeta bm) {
        return new BlockSpec().setObject(bm.getBlock(), bm.getBlockMeta());
    }

    class BlockSpecSortKey implements Comparable<BlockSpecSortKey> {

        static Object2ObjectOpenHashMap<String, String> NAMES_MAP = new Object2ObjectOpenHashMap<>();

        private final boolean hasStack;
        private final String name;
        private final int damage;
        private final boolean hasTag;
        private final int tagHash;

        public BlockSpecSortKey(ImmutableBlockSpec spec) {
            ItemStack stack = spec.toStack(1);

            if (stack == null) {
                hasStack = false;
                name = "";
                damage = -1;
                hasTag = false;
                tagHash = -1;
            } else {
                assert stack.getItem() != null;
                hasStack = true;
                name = NAMES_MAP.computeIfAbsent(stack.getItem().delegate.name().toLowerCase(), k -> (String) k);
                damage = stack.itemDamage;
                hasTag = stack.hasTagCompound();
                tagHash = hasTag ? stack.getTagCompound().hashCode() : -1;
            }
        }

        @Override
        public int compareTo(@NotNull ImmutableBlockSpec.BlockSpecSortKey other) {
            if (!hasStack && other.hasStack) return -1;
            if (hasStack && !other.hasStack) return 1;
            if (!hasStack && !other.hasStack) return 0;

            int result;

            if (name != other.name) {
                result = name.compareTo(other.name);
                if (result != 0) return result;
            }

            result = Integer.compare(damage, other.damage);
            if (result != 0) return result;

            if (!hasTag && other.hasTag) return -1;
            if (hasTag && !other.hasTag) return 1;
            if (!hasTag && !other.hasTag) return 0;

            return Integer.compare(tagHash, other.tagHash);
        }
    }
}
