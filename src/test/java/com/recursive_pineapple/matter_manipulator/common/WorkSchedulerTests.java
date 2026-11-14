package com.recursive_pineapple.matter_manipulator.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Iterator;

import com.recursive_pineapple.matter_manipulator.common.utils.scheduler.ISchedulable;
import com.recursive_pineapple.matter_manipulator.common.utils.scheduler.WorkScheduler;
import org.junit.jupiter.api.Test;

class WorkSchedulerTests {

    @Test
    void runsWorkerUntilItYieldsAndRemovesCompletedWorkers() throws Exception {
        WorkScheduler scheduler = new WorkScheduler(0);
        TestWorker worker = new TestWorker(3, true);

        assertTrue(scheduler.scheduleWork(worker));
        assertTrue(scheduler.tryDoWork(1));

        assertEquals(3, worker.calls);
        assertFalse(worker.hasWork());
        assertEquals(1, worker.unscheduledCalls);
        assertFalse(scheduler.iterator().hasNext());
        assertEquals(1, scheduler.getLastTIck());
    }

    @Test
    void runsOtherWorkersWhenAWorkerYields() throws Exception {
        WorkScheduler scheduler = new WorkScheduler(0);
        TestWorker yieldingWorker = new TestWorker(2, false);
        TestWorker completingWorker = new TestWorker(1, true);

        scheduler.scheduleWork(yieldingWorker);
        scheduler.scheduleWork(completingWorker);
        scheduler.tryDoWork(4);

        assertEquals(2, yieldingWorker.calls);
        assertFalse(yieldingWorker.hasWork());
        assertEquals(1, yieldingWorker.unscheduledCalls);
        assertEquals(1, completingWorker.calls);
        assertFalse(completingWorker.hasWork());
        assertNull(yieldingWorker.getScheduler());
        assertEquals(1, completingWorker.unscheduledCalls);
    }

    @Test
    void movesWorkerFromItsPreviousScheduler() {
        WorkScheduler first = new WorkScheduler(0);
        WorkScheduler second = new WorkScheduler(0);
        TestWorker worker = new TestWorker(1, true);

        first.scheduleWork(worker);
        assertTrue(second.scheduleWork(worker));

        assertFalse(first.iterator().hasNext());
        assertSame(second, worker.getScheduler());
        assertEquals(1, worker.unscheduledCalls);
    }

    @Test
    void handlesWorkerExceptionsAndRemovesTheWorker() throws Exception {
        WorkScheduler scheduler = new WorkScheduler(0);
        Exception failure = new Exception("worker failed");
        FailingWorker worker = new FailingWorker(failure);
        ISchedulable[] failedWorker = new ISchedulable[1];
        Exception[] handledFailure = new Exception[1];
        scheduler.addErrorHandler((failed, exception) -> {
            failedWorker[0] = failed;
            handledFailure[0] = exception;
            return true;
        });
        scheduler.scheduleWork(worker);

        assertTrue(scheduler.tryDoWork(2));

        assertSame(worker, failedWorker[0]);
        assertSame(failure, handledFailure[0]);
        assertEquals(1, worker.unscheduledCalls);
        assertFalse(scheduler.iterator().hasNext());
    }

    @Test
    void propagatesUnhandledWorkerExceptionsAfterRemovingTheWorker() {
        WorkScheduler scheduler = new WorkScheduler(0);
        Exception failure = new Exception("worker failed");
        FailingWorker worker = new FailingWorker(failure);
        scheduler.scheduleWork(worker);

        Exception thrown = assertThrows(Exception.class, () -> scheduler.tryDoWork(3));

        assertSame(failure, thrown);
        assertEquals(1, worker.unscheduledCalls);
        assertFalse(scheduler.iterator().hasNext());
    }

    @Test
    void iteratorIsASnapshotOfScheduledWorkers() {
        WorkScheduler scheduler = new WorkScheduler(0);
        TestWorker first = new TestWorker(1, true);
        TestWorker second = new TestWorker(1, true);
        scheduler.scheduleWork(first);
        Iterator<ISchedulable> snapshot = scheduler.iterator();

        scheduler.scheduleWork(second);

        assertSame(first, snapshot.next());
        assertFalse(snapshot.hasNext());
        assertThrows(UnsupportedOperationException.class, snapshot::remove);
    }

    private static class TestWorker implements ISchedulable {

        private int remaining;
        private final boolean keepRunning;
        private int calls;
        int unscheduledCalls;
        private WorkScheduler scheduler;

        private TestWorker(int workUnits, boolean keepRunning) {
            this.remaining = workUnits;
            this.keepRunning = keepRunning;
        }

        @Override
        public boolean hasWork() {
            return remaining > 0;
        }

        @Override
        public boolean doWork(long tick) throws Exception {
            calls++;
            remaining--;
            return keepRunning && remaining > 0;
        }

        @Override
        public WorkScheduler getScheduler() {
            return scheduler;
        }

        @Override
        public void onScheduled(WorkScheduler scheduler) {
            this.scheduler = scheduler;
        }

        @Override
        public void onUnscheduled() {
            unscheduledCalls++;
            scheduler = null;
        }
    }

    private static final class FailingWorker extends TestWorker {

        private final Exception failure;

        private FailingWorker(Exception failure) {
            super(1, false);
            this.failure = failure;
        }

        @Override
        public boolean doWork(long tick) throws Exception {
            throw failure;
        }
    }
}
