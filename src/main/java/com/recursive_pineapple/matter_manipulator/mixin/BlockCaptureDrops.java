package com.recursive_pineapple.matter_manipulator.mixin;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.entity.item.EntityItem;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

public interface BlockCaptureDrops {

    void captureDrops();

    /** The captured item entities, which were never added to the world. */
    List<EntityItem> stopCapturingDropEntities();

    static void captureDrops(World world) {
        if (world instanceof BlockCaptureDrops drops) drops.captureDrops();
    }

    static List<EntityItem> stopCapturingDropEntities(World world) {
        if (world instanceof BlockCaptureDrops drops) {
            return drops.stopCapturingDropEntities();
        } else {
            return new ArrayList<>();
        }
    }

    static List<ItemStack> stopCapturingDrops(World world) {
        List<ItemStack> stacks = new ArrayList<>();

        for (EntityItem entity : stopCapturingDropEntities(world)) {
            stacks.add(entity.getEntityItem());
        }

        return stacks;
    }
}
