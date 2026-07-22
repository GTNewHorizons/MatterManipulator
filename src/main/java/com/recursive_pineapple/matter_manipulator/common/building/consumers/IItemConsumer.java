package com.recursive_pineapple.matter_manipulator.common.building.consumers;

import com.recursive_pineapple.matter_manipulator.common.building.BlockAnalyzer.IBlockApplyContext;
import com.recursive_pineapple.matter_manipulator.common.utils.BigItemStack;

/**
 * An inventory consumer helper that will provide items to MM when placing blocks
 */
public interface IItemConsumer {

    /**
     * Consumes the requested items and then return it
     *
     * @param ctx Context for the build, provides window to the inventory
     * @param in The requested item, decrease it by the amount successfully consumed
     * @param out The returned item, increase it by the amount successfully consumed
     */
    void consume(IBlockApplyContext ctx, BigItemStack in, BigItemStack out);
}
