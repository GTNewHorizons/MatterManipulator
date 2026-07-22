package com.recursive_pineapple.matter_manipulator.common.building.consumers;

import static com.recursive_pineapple.matter_manipulator.common.building.IPseudoInventory.CONSUME_PARTIAL;

import java.util.Collections;
import java.util.List;

import com.recursive_pineapple.matter_manipulator.common.building.BlockAnalyzer.IBlockApplyContext;
import com.recursive_pineapple.matter_manipulator.common.utils.BigItemStack;

public class DefaultItemConsumer implements IItemConsumer {

    @Override
    public void consume(IBlockApplyContext ctx, BigItemStack in, BigItemStack out) {
        List<BigItemStack> extractedStacks = ctx.tryConsumeItems(Collections.singletonList(in.copy()), CONSUME_PARTIAL).right();
        if (extractedStacks != null && !extractedStacks.isEmpty()) {
            in.decStackSize(extractedStacks.get(0).getStackSize());
            out.incStackSize(extractedStacks.get(0).getStackSize());
        }
    }
}
