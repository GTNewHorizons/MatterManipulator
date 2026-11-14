package com.recursive_pineapple.matter_manipulator.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.recursive_pineapple.matter_manipulator.common.utils.scheduler.FSM;
import com.recursive_pineapple.matter_manipulator.common.utils.scheduler.WorkScheduler;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class FSMTests {

    @Test
    void processesTransitionsEventsAndStateChangeListeners() {
        TestFSM fsm = new TestFSM();
        List<TestFSM> stateChanges = new ArrayList<>();
        fsm.onStateChange(stateChanges::add);

        Assertions.assertTrue(fsm.doWork(10));
        Assertions.assertEquals(TestFSM.State.RUNNING, fsm.getCurrentState());
        Assertions.assertEquals(TestFSM.State.IDLE, fsm.getPreviousState());
        Assertions.assertEquals(10, fsm.getLastWorkingTick());
        assertEquals(1, fsm.firstEventCalls);
        assertEquals(1, fsm.secondEventCalls);
        assertEquals(Arrays.asList(fsm), stateChanges);

        Assertions.assertTrue(fsm.doWork(11));
        assertEquals(2, fsm.firstEventCalls);
        assertEquals(2, fsm.secondEventCalls);
        assertSame(TestFSM.State.RUNNING, fsm.getPreviousState());

        Assertions.assertFalse(fsm.doWork(12));
        Assertions.assertEquals(TestFSM.State.FINISHED, fsm.getCurrentState());
        Assertions.assertFalse(fsm.hasWork());
        assertEquals(Arrays.asList(fsm, fsm), stateChanges);
    }

    @Test
    void schedulerRunsFsmUntilItsFinalState() throws Exception {
        WorkScheduler scheduler = new WorkScheduler(0);
        TestFSM fsm = new TestFSM();

        scheduler.scheduleWork(fsm);
        scheduler.tryDoWork(7);

        Assertions.assertEquals(TestFSM.State.FINISHED, fsm.getCurrentState());
        assertEquals(2, fsm.firstEventCalls);
        assertFalse(scheduler.iterator().hasNext());
        Assertions.assertEquals(7, fsm.getLastWorkingTick());
    }

    @Test
    void pauseAndResumeControlWork() {
        TestFSM fsm = new TestFSM();
        fsm.pause();

        Assertions.assertFalse(fsm.doWork(1));
        Assertions.assertEquals(TestFSM.State.IDLE, fsm.getCurrentState());
        Assertions.assertEquals(0, fsm.getLastWorkingTick());

        fsm.resume();

        Assertions.assertTrue(fsm.doWork(2));
        Assertions.assertEquals(TestFSM.State.RUNNING, fsm.getCurrentState());
        Assertions.assertEquals(2, fsm.getLastWorkingTick());
    }

    @Test
    void eventFailuresAreWrappedWithTheMethodContext() {
        FailingFSM fsm = new FailingFSM();

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> fsm.doWork(1));

        assertTrue(thrown.getMessage().contains("fail"));
        assertSame(fsm.failure, thrown.getCause());
    }

    private static class TestFSM extends FSM<TestFSM.State, TestFSM> {

        private enum State {
            IDLE,
            RUNNING,
            FINISHED
        }

        private int firstEventCalls;
        private int secondEventCalls;

        private TestFSM() {
            super(State.IDLE, State.FINISHED);
        }

        @Transition(from = "IDLE", to = "RUNNING")
        private boolean start() {
            return true;
        }

        @Event("RUNNING")
        private boolean firstEvent() {
            firstEventCalls++;
            return true;
        }

        @Event("RUNNING")
        private boolean secondEvent() {
            secondEventCalls++;
            return false;
        }

        @Transition(from = "RUNNING", to = "FINISHED")
        private boolean finishAfterTwoEvents() {
            return firstEventCalls >= 2;
        }
    }

    private static final class FailingFSM extends FSM<FailingFSM.State, FailingFSM> {

        private enum State {
            START,
            FINISHED
        }

        private final RuntimeException failure = new RuntimeException("event failed");

        private FailingFSM() {
            super(State.START, State.FINISHED);
        }

        @Event("START")
        private boolean fail() {
            throw failure;
        }
    }
}
