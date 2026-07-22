package com.recursive_pineapple.matter_manipulator.common.building.consumers;

import static com.recursive_pineapple.matter_manipulator.common.building.IPseudoInventory.*;

import java.util.Collections;
import java.util.List;

import net.minecraft.item.ItemStack;

import appeng.api.AEApi;
import appeng.api.util.AEColor;
import appeng.api.util.AEColoredItemDefinition;

import com.recursive_pineapple.matter_manipulator.common.building.BlockAnalyzer;
import com.recursive_pineapple.matter_manipulator.common.utils.BigItemStack;

/**
 * A consumer that can consume AE cables with any color
 */
public class AECableItemConsumer implements IItemConsumer {

    public static final AEColoredItemDefinition AE_GLASS_CABLE = AEApi.instance().definitions().parts().cableGlass();
    public static final AEColoredItemDefinition AE_COVERED_CABLE = AEApi.instance().definitions().parts().cableCovered();
    public static final AEColoredItemDefinition AE_SMART_CABLE = AEApi.instance().definitions().parts().cableSmart();
    public static final AEColoredItemDefinition AE_DENSE_SMART_CABLE = AEApi.instance().definitions().parts().cableDense();
    public static final AEColoredItemDefinition AE_DENSE_COVERED_CABLE = AEApi.instance().definitions().parts().cableDenseCovered();

    @Override
    public void consume(BlockAnalyzer.IBlockApplyContext ctx, BigItemStack in, BigItemStack out) {
        if (in.getItem() != AE_GLASS_CABLE.item(AEColor.Transparent)) return;

        AEColoredItemDefinition definition;
        if (in.getItemDamage() <= AE_GLASS_CABLE.stack(AEColor.Transparent, 1).getItemDamage()) {
            definition = AE_GLASS_CABLE;
        } else if (in.getItemDamage() <= AE_COVERED_CABLE.stack(AEColor.Transparent, 1).getItemDamage()) {
            definition = AE_COVERED_CABLE;
        } else if (in.getItemDamage() <= AE_SMART_CABLE.stack(AEColor.Transparent, 1).getItemDamage()) {
            definition = AE_SMART_CABLE;
        } else if (in.getItemDamage() <= AE_DENSE_SMART_CABLE.stack(AEColor.Transparent, 1).getItemDamage()) {
            definition = AE_DENSE_SMART_CABLE;
        } else {
            definition = AE_DENSE_COVERED_CABLE;
        }

        // Start searching from fluix cables
        for (int color = AEColor.Transparent.ordinal(); color >= 0; color--) {
            ItemStack cableStack = definition.stack(AEColor.fromOrdinal(color), 1);
            BigItemStack cableBigStack = BigItemStack.create(cableStack).setStackSize(in.getStackSize());

            List<BigItemStack> extractedStacks = ctx.tryConsumeItems(Collections.singletonList(cableBigStack), CONSUME_PARTIAL).right();
            if (!extractedStacks.isEmpty()) {
                BigItemStack extracted = extractedStacks.get(0);

                in.decStackSize(extracted.getStackSize());
                out.incStackSize(extracted.getStackSize());
            }

            if (in.getStackSize() <= 0) return;
        }
    }
}
