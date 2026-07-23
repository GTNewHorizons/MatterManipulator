package com.recursive_pineapple.matter_manipulator.common.building.consumers;

import com.recursive_pineapple.matter_manipulator.common.building.IPseudoInventory;
import com.recursive_pineapple.matter_manipulator.common.utils.BigItemStack;

/**
 * An inventory consumer helper that will provide items to MM when placing blocks
 */
public interface IItemConsumer {

    /**
     * Consumes the requested items and then return it
     *
     * @param inv The inventory to consume items from
     * @param in The requested item, decrease it by the amount successfully consumed
     * @param out The returned item, increase it by the amount successfully consumed
     * @param flags The flags (only {@link IPseudoInventory#CONSUME_SIMULATED} and
     *        {@link IPseudoInventory#CONSUME_IGNORE_CREATIVE})
     */
    void consume(IPseudoInventory inv, BigItemStack in, BigItemStack out, int flags);
}
