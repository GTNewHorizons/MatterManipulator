package com.recursive_pineapple.matter_manipulator.common.building.consumers;

import com.recursive_pineapple.matter_manipulator.common.building.BlockAnalyzer.IBlockApplyContext;
import com.recursive_pineapple.matter_manipulator.common.utils.BigItemStack;

/**
 * An inventory consumer helper that will provide items to MM when placing blocks
 */
public interface IConsumer {

    void consume(IBlockApplyContext ctx, BigItemStack in, BigItemStack out);
}
