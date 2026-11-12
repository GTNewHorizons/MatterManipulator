package com.recursive_pineapple.matter_manipulator.common.utils.scheduler;

/**
 * A worker that a {@link WorkScheduler} processes incrementally
 * <p> {@link #hasWork()} reports whether work remains </p>
 * <p> {@link #doWork(long)} returns whether to continue this pass </p>
 */
public interface ISchedulable {

    /**
     * @return {@code true} if work remains
     */
    boolean hasWork();

    /**
     * @param tick the current scheduler tick
     * @return {@code true} to continue this pass, {@code false} to yield
     */
    boolean doWork(long tick);

    /**
     * @param scheduler the scheduler for this worker
     */
    void setScheduler(WorkScheduler scheduler);

    /**
     * @return this worker's scheduler, or {@code null}
     */
    WorkScheduler getScheduler();
}
