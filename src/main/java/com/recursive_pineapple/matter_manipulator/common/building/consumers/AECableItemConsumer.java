package com.recursive_pineapple.matter_manipulator.common.building.consumers;

import static com.recursive_pineapple.matter_manipulator.common.building.IPseudoInventory.*;

import java.util.Collections;
import java.util.List;

import net.minecraft.item.ItemStack;

import appeng.api.util.AEColor;
import appeng.api.util.AEColoredItemDefinition;

import com.recursive_pineapple.matter_manipulator.common.building.IPseudoInventory;
import com.recursive_pineapple.matter_manipulator.common.utils.BigItemStack;
import com.recursive_pineapple.matter_manipulator.common.utils.MMUtils;

/**
 * A consumer that can consume AE cables with any color
 */
public class AECableItemConsumer implements IItemConsumer {

    @Override
    public void consume(IPseudoInventory inv, BigItemStack in, BigItemStack out, int flags) {
        AEColoredItemDefinition definition = MMUtils.getAECableDefinition(in.getItemStack());
        if (definition == null) return;

        // Start searching from fluix cables
        for (int color = AEColor.Transparent.ordinal(); color >= 0; color--) {
            ItemStack cableStack = definition.stack(AEColor.fromOrdinal(color), 1);
            if (cableStack.getItemDamage() == in.getItemDamage()) continue;

            BigItemStack cableBigStack = BigItemStack.create(cableStack).setStackSize(in.getStackSize());

            List<BigItemStack> extractedStacks = inv.tryConsumeItems(Collections.singletonList(cableBigStack), CONSUME_PARTIAL | flags).right();
            if (!extractedStacks.isEmpty()) {
                BigItemStack extracted = extractedStacks.get(0);

                in.decStackSize(extracted.getStackSize());
                out.incStackSize(extracted.getStackSize());
            }

            if (in.getStackSize() <= 0) return;
        }
    }
}
