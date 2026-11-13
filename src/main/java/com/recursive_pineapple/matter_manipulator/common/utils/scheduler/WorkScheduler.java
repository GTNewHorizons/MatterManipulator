package com.recursive_pineapple.matter_manipulator.common.utils.scheduler;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;

import com.recursive_pineapple.matter_manipulator.GlobalMMConfig;

/**
 * Runs scheduled workers incrementally, up to a per-tick time budget.
 */
public class WorkScheduler {

    public static WorkScheduler INSTANCE = new WorkScheduler(GlobalMMConfig.BuildingConfig.schedulerBudgetMicros);

    public final long budgetMicros;

    private long lastTick;
    private long elapsedThisTick;

    private final Set<ISchedulable> workers = new LinkedHashSet<>();

    public WorkScheduler(long budgetMicros) {
        this.budgetMicros = budgetMicros;
    }

    /**
     * Adds a worker to this scheduler, moving it from its previous scheduler.
     *
     * @param worker the worker to schedule
     * @return {@code true} if the worker was added
     */
    public boolean scheduleWork(ISchedulable worker) {
        if (worker.getScheduler() == this) return false;
        if (worker.getScheduler() != null) {
            worker.getScheduler().removeWork(worker);
        }

        boolean added = workers.add(worker);
        worker.onScheduled(this);

        return added;
    }

    /**
     * Removes a worker from this scheduler.
     *
     * @return {@code true} if the worker was removed
     */
    public boolean removeWork(ISchedulable worker) {
        worker.onUnscheduled();
        return workers.remove(worker);
    }

    public boolean canDoWork(long tick) {
        if (budgetMicros == 0) return true;

        if (tick > lastTick) {
            elapsedThisTick = 0;
            lastTick = tick;
        }

        return budgetMicros > elapsedThisTick;
    }

    /**
     * Runs scheduled work if this tick's time budget allows it.
     *
     * @param tick the current tick
     * @return {@code true} if the scheduler ran; {@code false} if the budget is spent
     */
    public boolean tryDoWork(long tick) {
        if (!canDoWork(tick)) return false;

        doWork();

        return true;
    }

    public Iterator<ISchedulable> iterator() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(workers)).iterator();
    }

    protected void doWork() {
        long start = System.nanoTime();
        long now = start;

        Iterator<ISchedulable> iterator = iterator();

        boolean hasKeep = true;

        while (!workers.isEmpty() && (budgetMicros == 0 || (now - start) / 1000 < budgetMicros)) {
            if (!iterator.hasNext()) {
                if (!hasKeep) break;

                iterator = iterator();
                hasKeep = false;
            }

            ISchedulable worker = iterator.next();

            while (worker.hasWork() && (budgetMicros == 0 || (now - start) / 1000 < budgetMicros)) {
                boolean keep = worker.doWork(lastTick) && worker.hasWork();
                if (budgetMicros != 0) now = System.nanoTime();

                if (keep) {
                    hasKeep = true;
                } else {
                    break;
                }
            }

            if (!worker.hasWork()) removeWork(worker);
        }

        if (budgetMicros != 0) elapsedThisTick += (now - start) / 1000;
    }
}
