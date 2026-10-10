package com.recursive_pineapple.matter_manipulator.common.building.consumers;

import static com.recursive_pineapple.matter_manipulator.common.building.IPseudoInventory.*;

import java.util.Collections;
import java.util.List;

import net.minecraft.item.ItemStack;

import appeng.api.AEApi;
import appeng.api.util.AEColor;
import appeng.api.util.AEColoredItemDefinition;

import com.recursive_pineapple.matter_manipulator.common.building.IPseudoInventory;
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

    private static final AEColoredItemDefinition[] AE_CABLES = {
        AE_GLASS_CABLE, AE_COVERED_CABLE, AE_SMART_CABLE, AE_DENSE_SMART_CABLE, AE_DENSE_COVERED_CABLE
    };

    @Override
    public void consume(IPseudoInventory inv, BigItemStack in, BigItemStack out, int flags) {
        AEColoredItemDefinition definition = getCableDefinition(in.getItemStack());
        if (definition == null) return;

        boolean isPlanning = (flags & CONSUME_SIMULATED) == 1 && (flags & CONSUME_IGNORE_CREATIVE) == 1;

        // Start searching from fluix cables
        for (int color = AEColor.Transparent.ordinal(); color >= 0; color--) {
            // We're in planning, since colored cables are crafted using fluix, we skip them
            if (isPlanning && color == AEColor.Transparent.ordinal()) continue;

            ItemStack cableStack = definition.stack(AEColor.fromOrdinal(color), 1);
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

    /** Every AE part is the same item, so a part is only a cable if it matches one of the cable variants. */
    private static AEColoredItemDefinition getCableDefinition(ItemStack stack) {
        for (AEColoredItemDefinition definition : AE_CABLES) {
            for (AEColor color : AEColor.values()) {
                if (definition.sameAs(color, stack)) return definition;
            }
        }

        return null;
    }
}
