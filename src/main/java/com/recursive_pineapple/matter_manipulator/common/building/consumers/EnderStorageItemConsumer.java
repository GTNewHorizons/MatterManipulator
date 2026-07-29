package com.recursive_pineapple.matter_manipulator.common.building.consumers;

import static com.recursive_pineapple.matter_manipulator.common.building.IPseudoInventory.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraftforge.common.util.Constants;

import com.recursive_pineapple.matter_manipulator.common.building.IPseudoInventory;
import com.recursive_pineapple.matter_manipulator.common.building.InteropConstants;
import com.recursive_pineapple.matter_manipulator.common.utils.BigItemStack;

import codechicken.enderstorage.EnderStorage;

/**
 * A consumer that can consume Ender Chest/Tank with any color and setting
 */
public class EnderStorageItemConsumer implements IItemConsumer {

    @Override
    public void consume(IPseudoInventory inv, BigItemStack in, BigItemStack out, int flags) {
        if (InteropConstants.ENDER_STORAGE.getItem() != in.getItem()) return;

        boolean isTank = in.meta >= 4096;
        boolean isPrivate = in.tag != null && in.tag.hasKey("owner", Constants.NBT.TAG_STRING);
        boolean isPlanning = (flags & CONSUME_SIMULATED) == 1 && (flags & CONSUME_IGNORE_CREATIVE) == 1;

        long initial = in.getStackSize();
        long specialAmount = 0;

        List<BigItemStack> toExtract = new ArrayList<>();
        List<BigItemStack> specialStacks = new ArrayList<>();

        // Start searching from frequency 0
        for (int freq = 0; freq < 4096; freq++) {
            BigItemStack simulateStack = in.copy();
            simulateStack.meta = isTank ? 4096 + freq : freq;
            simulateStack.tag = null;

            List<BigItemStack> extractSimulated = inv.tryConsumeItems(
                Collections.singletonList(simulateStack),
                CONSUME_FUZZY | CONSUME_PARTIAL | CONSUME_SIMULATED | flags
            ).right();

            if (extractSimulated != null) {
                for (BigItemStack extracted : extractSimulated) {
                    boolean isExtractedPrivate = extracted.tag != null &&
                        extracted.tag.hasKey("owner", Constants.NBT.TAG_STRING);

                    // We're in planning and freq 0 non-private is used for crafting, skip it
                    if (isPlanning && !isExtractedPrivate && freq == 0) continue;

                    if (isPrivate == isExtractedPrivate) {
                        in.decStackSize(extracted.getStackSize());
                        toExtract.add(extracted);
                    } else {
                        specialStacks.add(extracted);
                        specialAmount += extracted.stackSize;
                    }
                }

                if (in.getStackSize() <= 0) break;
            }
        }

        specialAmount = Math.min(in.getStackSize(), specialAmount);

        if (specialAmount > 0) {
            if (isPrivate) {
                BigItemStack personalItem = BigItemStack.create(EnderStorage.getPersonalItem())
                    .setStackSize(specialAmount);
                List<BigItemStack> personalItems = inv.tryConsumeItems(
                    Collections.singletonList(personalItem),
                    CONSUME_PARTIAL | flags
                ).right();

                if (personalItems != null && !personalItems.isEmpty()) {
                    specialAmount = personalItems.get(0).getStackSize();
                } else {
                    specialAmount = 0;
                }
            }

            long initialSpecialAmount = specialAmount;

            for (BigItemStack stack : specialStacks) {
                long size = Math.min(specialAmount, stack.getStackSize());

                stack.setStackSize(size);
                toExtract.add(stack);
                in.decStackSize(size);
                specialAmount -= size;

                if (in.getStackSize() <= 0) break;
                if (specialAmount <= 0) break;
            }

            specialAmount = initialSpecialAmount - specialAmount;
        }

        boolean success = inv.tryConsumeItems(toExtract, flags).leftBoolean();

        if (!success) {
            // Something gone wrong, reset to former state
            in.setStackSize(initial);
        } else {
            out.incStackSize(initial - in.getStackSize());
        }

        boolean shouldReturnPersonalItem = (!success && isPrivate) || (success && !isPrivate);
        if (!isPlanning && shouldReturnPersonalItem) {
            // Give player personalItems if success and not private
            // or return personalItems if private and failed

            BigItemStack personalItem = BigItemStack.create(EnderStorage.getPersonalItem())
                .setStackSize(specialAmount);
            inv.givePlayerItems(Collections.singletonList(personalItem));
        }
    }
}
