package com.recursive_pineapple.matter_manipulator.common.building.consumers;

import static com.recursive_pineapple.matter_manipulator.common.building.IPseudoInventory.CONSUME_PARTIAL;

import java.util.Collections;
import java.util.List;

import com.recursive_pineapple.matter_manipulator.common.building.IPseudoInventory;
import com.recursive_pineapple.matter_manipulator.common.utils.BigItemStack;

public class DefaultItemConsumer implements IItemConsumer {

    @Override
    public void consume(IPseudoInventory inv, BigItemStack in, BigItemStack out, int flags) {
        List<BigItemStack> extractedStacks = inv.tryConsumeItems(Collections.singletonList(in.copy()), CONSUME_PARTIAL | flags).right();
        if (extractedStacks != null && !extractedStacks.isEmpty()) {
            in.decStackSize(extractedStacks.get(0).getStackSize());
            out.incStackSize(extractedStacks.get(0).getStackSize());
        }
    }
}
