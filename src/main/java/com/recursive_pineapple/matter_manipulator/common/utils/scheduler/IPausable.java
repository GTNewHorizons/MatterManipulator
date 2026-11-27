package com.recursive_pineapple.matter_manipulator.common.utils.scheduler;

/**
 * A worker that can be paused and resumed
 */
public interface IPausable {

    /**
     * @return {@code true} if this worker is paused
     */
    boolean isPaused();

    /**
     * Pause the worker
     */
    void pause();

    /**
     * Resume the worker
     */
    void resume();
}
