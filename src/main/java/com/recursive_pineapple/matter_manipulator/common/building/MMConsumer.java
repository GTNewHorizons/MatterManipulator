package com.recursive_pineapple.matter_manipulator.common.building;

import java.util.Comparator;

import com.recursive_pineapple.matter_manipulator.common.building.consumers.AECableConsumer;
import com.recursive_pineapple.matter_manipulator.common.building.consumers.DefaultConsumer;
import com.recursive_pineapple.matter_manipulator.common.building.consumers.IConsumer;
import com.recursive_pineapple.matter_manipulator.common.utils.BigItemStack;

import it.unimi.dsi.fastutil.ints.IntObjectImmutablePair;
import it.unimi.dsi.fastutil.objects.ObjectAVLTreeSet;
import it.unimi.dsi.fastutil.objects.ObjectSortedSet;

/**
 * An inventory consumer that will provide items to MM when placing blocks
 */
public class MMConsumer {

    private static final ObjectSortedSet<IntObjectImmutablePair<IConsumer>> consumers = new ObjectAVLTreeSet<>(
        Comparator.comparingInt(IntObjectImmutablePair::leftInt)
    );

    /**
     * Registers a consumer to be used in MM
     *
     * @param priority The priority in which the consumer will be called
     * @param consumer The consumer implementation
     */
    public static void registerConsumer(int priority, IConsumer consumer) {
        consumers.add(new IntObjectImmutablePair<>(priority, consumer));
    }

    static {
        registerConsumer(Integer.MIN_VALUE, new DefaultConsumer());
    }

    /**
     * Try to consume provided item from inventory
     */
    public static BigItemStack consume(BlockAnalyzer.IBlockApplyContext ctx, BigItemStack item) {
        BigItemStack out = item.copy().setStackSize(0);

        for (IntObjectImmutablePair<IConsumer> pair : consumers) {
            IConsumer consumer = pair.right();
            consumer.consume(ctx, item, out);

            if (item.getStackSize() <= 0) break;
        }

        return out.getStackSize() > 0 ? out : null;
    }
}
