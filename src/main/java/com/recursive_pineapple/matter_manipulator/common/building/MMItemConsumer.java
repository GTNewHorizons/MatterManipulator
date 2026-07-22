package com.recursive_pineapple.matter_manipulator.common.building;

import java.util.Comparator;

import com.recursive_pineapple.matter_manipulator.common.building.consumers.AECableItemConsumer;
import com.recursive_pineapple.matter_manipulator.common.building.consumers.DefaultItemConsumer;
import com.recursive_pineapple.matter_manipulator.common.building.consumers.IItemConsumer;
import com.recursive_pineapple.matter_manipulator.common.utils.BigItemStack;
import com.recursive_pineapple.matter_manipulator.common.utils.Mods;

import it.unimi.dsi.fastutil.ints.IntObjectImmutablePair;
import it.unimi.dsi.fastutil.objects.ObjectAVLTreeSet;
import it.unimi.dsi.fastutil.objects.ObjectSortedSet;

/**
 * An inventory consumer that will provide items to MM when placing blocks
 */
public class MMItemConsumer {

    private static final ObjectSortedSet<IntObjectImmutablePair<IItemConsumer>> consumers = new ObjectAVLTreeSet<>(
        Comparator.comparingInt(IntObjectImmutablePair::leftInt)
    );

    /**
     * Registers a consumer to be used in MM
     *
     * @param priority The priority in which the consumer will be called
     * @param consumer The consumer implementation
     */
    public static void registerConsumer(int priority, IItemConsumer consumer) {
        consumers.add(new IntObjectImmutablePair<>(priority, consumer));
    }

    static {
        registerConsumer(Integer.MIN_VALUE, new DefaultItemConsumer());
        if (Mods.AppliedEnergistics2.isModLoaded() && Mods.GregTech.isModLoaded()) {
            registerConsumer(0, new AECableItemConsumer());
        }
    }

    /**
     * Try to consume provided item from inventory
     */
    public static BigItemStack consume(BlockAnalyzer.IBlockApplyContext ctx, BigItemStack item) {
        BigItemStack out = item.copy().setStackSize(0);

        for (IntObjectImmutablePair<IItemConsumer> pair : consumers) {
            IItemConsumer consumer = pair.right();
            consumer.consume(ctx, item, out);

            if (item.getStackSize() <= 0) break;
        }

        return out.getStackSize() > 0 ? out : null;
    }
}
