package com.recursive_pineapple.matter_manipulator.common.utils.scheduler;

import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.BiPredicate;

/**
 * Runs scheduled workers incrementally, up to a per-tick time budget.
 */
public class WorkScheduler {

    public final long budgetMicros;

    private long lastTick;
    private long elapsedThisTick;

    private final Set<ISchedulable> workers = new LinkedHashSet<>();
    private final Set<BiPredicate<ISchedulable, Throwable>> errorHandlers = new HashSet<>();

    public WorkScheduler(long budgetMicros) {
        this.budgetMicros = budgetMicros;
    }

    public long getLastTick() {
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
    public boolean tryDoWork(long tick) throws Throwable {
        if (!canDoWork(tick)) return false;

        doWork();

        return true;
    }

    public Iterator<ISchedulable> iterator() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(workers)).iterator();
    }

    public Iterator<BiPredicate<ISchedulable, Throwable>> errorHandlerIterator() {
        return Collections.unmodifiableSet(new HashSet<>(errorHandlers)).iterator();
    }

    public boolean addErrorHandler(BiPredicate<ISchedulable, Throwable> handler) {
        return errorHandlers.add(handler);
    }

    public boolean removeErrorHandler(BiPredicate<ISchedulable, Throwable> handler) {
        return errorHandlers.remove(handler);
    }

    protected void doWork() throws Throwable {
        long start = budgetMicros == 0 ? 0 : System.nanoTime();
        long now = start;

        Iterator<ISchedulable> iterator = iterator();
        boolean hasKeep = false;
        long sliceBudget = (budgetMicros - elapsedThisTick) / Math.max(1, workers.size());

        while (!workers.isEmpty() && (budgetMicros == 0 || (now - start) / 1000 < budgetMicros - elapsedThisTick)) {
            if (!iterator.hasNext()) {
                if (!hasKeep) break;

                iterator = iterator();
                hasKeep = false;
                sliceBudget = (budgetMicros - elapsedThisTick - (now - start) / 1000) / Math.max(1, workers.size());
            }

            ISchedulable worker = iterator.next();

            long sliceStart = budgetMicros == 0 ? start : System.nanoTime();
            now = sliceStart;

            while (
                worker.hasWork() && (budgetMicros == 0 ||
                    ((now - sliceStart) / 1000 < sliceBudget &&
                        (now - start) / 1000 < budgetMicros - elapsedThisTick))
            ) {
                boolean keep;

                try {
                    keep = worker.doWork(lastTick);
                } catch (Throwable e) {
                    removeWork(worker);
                    onError(worker, e);

                    // In case the error is to-be propagated
                    hasKeep = true;

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

    protected void onError(ISchedulable worker, Throwable throwable) throws Throwable {
        boolean handled = false;

        for (BiPredicate<ISchedulable, Throwable> handler : errorHandlers) {
            handled = handler.test(worker, throwable) || handled;
        }

        if (!handled) throw throwable;
    }
}
