package com.recursive_pineapple.matter_manipulator.common.hooks;

import net.minecraft.server.MinecraftServer;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

import com.gtnewhorizon.gtnhlib.eventbus.EventBusSubscriber;
import com.recursive_pineapple.matter_manipulator.GlobalMMConfig;
import com.recursive_pineapple.matter_manipulator.MMMod;
import com.recursive_pineapple.matter_manipulator.common.utils.scheduler.WorkScheduler;

@EventBusSubscriber
public class MMTickHandler {

    private static WorkScheduler SCHEDULER;

    public static WorkScheduler getScheduler() {
        if (SCHEDULER == null) SCHEDULER = new WorkScheduler(GlobalMMConfig.BuildingConfig.schedulerBudgetMicros);
        return SCHEDULER;
    }

    public static void resetScheduler() {
        SCHEDULER = null;
    }

    @SubscribeEvent
    public static void onWorldTick(TickEvent.WorldTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!GlobalMMConfig.BuildingConfig.useWorkScheduler) return;

        if (SCHEDULER == null) return;

        // In case when the scheduler's budget config is changed in-game
        if (SCHEDULER.budgetMicros != GlobalMMConfig.BuildingConfig.schedulerBudgetMicros) {
            WorkScheduler newScheduler = new WorkScheduler(GlobalMMConfig.BuildingConfig.schedulerBudgetMicros);

            SCHEDULER.iterator().forEachRemaining(newScheduler::scheduleWork);
            SCHEDULER = newScheduler;
        }

        try {
            SCHEDULER.tryDoWork(MinecraftServer.getServer().getTickCounter());
        } catch (Throwable e) {
            MMMod.LOG.error("Unhandled worker exception", e);
        }
    }
}
