package com.recursive_pineapple.matter_manipulator.common.utils.scheduler;

import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.BiPredicate;

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
    private final Set<BiPredicate<ISchedulable, Exception>> errorHandlers = new HashSet<>();

    public WorkScheduler(long budgetMicros) {
        this.budgetMicros = budgetMicros;
    }

    public long getLastTIck() {
        return lastTick;
    }

    /**
     * Adds a worker to this scheduler, moving it from its previous scheduler.
     *
     * @param worker the worker to schedule
     * @return {@code true} if the worker was added
     */
    public boolean scheduleWork(ISchedulable worker) {
        if (worker.getScheduler() == this && workers.contains(worker)) return false;
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
        if (!workers.contains(worker)) return false;
        worker.onUnscheduled();
        return workers.remove(worker);
    }

    public boolean canDoWork(long tick) {
        if (tick > lastTick) {
            elapsedThisTick = 0;
            lastTick = tick;
        }

        if (budgetMicros == 0) return true;

        return budgetMicros > elapsedThisTick;
    }

    /**
     * Runs scheduled work if this tick's time budget allows it.
     *
     * @param tick the current tick
     * @return {@code true} if the scheduler ran; {@code false} if the budget is spent
     */
    public boolean tryDoWork(long tick) throws Exception {
        if (!canDoWork(tick)) return false;

        doWork();

        return true;
    }

    public Iterator<ISchedulable> iterator() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(workers)).iterator();
    }

    public boolean addErrorHandler(BiPredicate<ISchedulable, Exception> handler) {
        return errorHandlers.add(handler);
    }

    public boolean removeErrorHandler(BiPredicate<ISchedulable, Exception> handler) {
        return errorHandlers.remove(handler);
    }

    protected void doWork() throws Exception {
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
                boolean keep;

                try {
                    keep = worker.doWork(lastTick);
                } catch (Exception e) {
                    removeWork(worker);
                    onError(worker, e);
                    break;
                }

                keep &= worker.hasWork();

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

    protected void onError(ISchedulable worker, Exception exception) throws Exception {
        boolean handled = false;

        for (BiPredicate<ISchedulable, Exception> handler : errorHandlers) {
            handled = handler.test(worker, exception) || handled;
        }

        if (!handled) throw exception;
    }
}
