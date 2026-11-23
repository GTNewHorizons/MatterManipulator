package com.recursive_pineapple.matter_manipulator.common.utils.scheduler;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.jetbrains.annotations.NotNull;

import it.unimi.dsi.fastutil.Pair;
import it.unimi.dsi.fastutil.ints.IntReferenceImmutablePair;
import it.unimi.dsi.fastutil.objects.ReferenceReferenceImmutablePair;

/**
 * A schedulable finite-state machine that runs state events and transitions
 * incrementally, with support for pausing and resuming.
 */
public abstract class FSM<S extends Enum<S>, T extends FSM<S, T>> implements ISchedulable, IPausable {

    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();

    private static final ClassValue<Pair<List<Pair<String, MethodHandle>>, List<Pair<Pair<String, String>, MethodHandle>>>> EVENTS_AND_TRANSITIONS = new ClassValue<>() {

        @Override
        protected Pair<List<Pair<String, MethodHandle>>, List<Pair<Pair<String, String>, MethodHandle>>> computeValue(
            @NotNull Class<?> type
        ) {
            List<Pair<String, MethodHandle>> events = new ArrayList<>();
            List<Pair<Pair<String, String>, MethodHandle>> transitions = new ArrayList<>();

            for (Method method : type.getDeclaredMethods()) {
                Event event = method.getAnnotation(Event.class);
                Transition transition = method.getAnnotation(Transition.class);

                if (event == null && transition == null) continue;
                if (event != null && transition != null) {
                    throw new IllegalArgumentException("FSM method cannot be both an event and a transition: " + method);
                }
                if (method.getReturnType() != boolean.class || method.getParameterCount() != 0) {
                    throw new IllegalArgumentException("FSM methods must take no arguments and return boolean: " + method);
                }

                try {
                    method.setAccessible(true);

                    MethodHandle methodHandle = LOOKUP.unreflect(method);
                    methodHandle = methodHandle.asType(MethodType.methodType(boolean.class, FSM.class));

                    if (event != null) {
                        events.add(ReferenceReferenceImmutablePair.of(event.value(), methodHandle));
                    } else {
                        transitions.add(
                            ReferenceReferenceImmutablePair.of(
                                ReferenceReferenceImmutablePair.of(transition.from(), transition.to()),
                                methodHandle
                            )
                        );
                    }
                } catch (Throwable t) {
                    throw new ExceptionInInitializerError(t);
                }
            }

            return ReferenceReferenceImmutablePair.of(events, transitions);
        }
    };

    private long lastWorkingTick;

    private S previousState;
    private S currentState;

    private boolean paused;
    private WorkScheduler scheduler;

    private final S initialState;
    private final S finalState;

    protected final List<Consumer<T>> stateChangeListeners;
    protected final S[] stateValues;
    protected final List<MethodHandle>[] stateEvents;
    protected final List<IntReferenceImmutablePair<MethodHandle>>[] stateTransitions;

    protected FSM(S initialState, S finalState) {
        this.initialState = initialState;
        this.finalState = finalState;

        this.currentState = initialState;

        this.stateChangeListeners = new ArrayList<>();

        this.stateValues = initialState.getDeclaringClass().getEnumConstants();
        this.stateEvents = (List<MethodHandle>[]) new List<?>[stateValues.length];
        this.stateTransitions = (List<IntReferenceImmutablePair<MethodHandle>>[]) new List<?>[stateValues.length];

        discoverStateMethods();
    }

    public long getLastWorkingTick() {
        return lastWorkingTick;
    }

    public S getPreviousState() {
        return previousState;
    }

    public S getCurrentState() {
        return currentState;
    }

    public S getInitialState() {
        return initialState;
    }

    public S getFinalState() {
        return finalState;
    }

    @Override
    public boolean isPaused() {
        return paused;
    }

    @Override
    public void pause() {
        paused = true;
    }

    @Override
    public void resume() {
        paused = false;
    }

    @Override
    public void onScheduled(WorkScheduler scheduler) {
        if (this.scheduler != scheduler) {
            this.scheduler = scheduler;
        }
    }

    @Override
    public void onUnscheduled() {}

    protected void unscheduleChild(ISchedulable child) {
        if (child == null || !child.hasWork()) return;

        WorkScheduler childScheduler = child.getScheduler();
        if (childScheduler != null) childScheduler.removeWork(child);
    }

    @Override
    public WorkScheduler getScheduler() {
        return scheduler;
    }

    @Override
    public boolean hasWork() {
        return currentState != finalState;
    }

    @Override
    public boolean doWork(long tick) throws Throwable {
        if (!hasWork()) return false;
        if (isPaused()) return false;

        lastWorkingTick = tick;
        previousState = currentState;

        processStateTransitions();

        if (previousState != currentState) notifyStateChangeListeners();

        // State Change listeners may pause the FSM
        if (isPaused()) return false;

        return processStateEvents();
    }

    protected void processStateTransitions() throws Throwable {
        List<IntReferenceImmutablePair<MethodHandle>> transitions = stateTransitions[currentState.ordinal()];

        if (transitions == null) return;

        for (IntReferenceImmutablePair<MethodHandle> transition : transitions) {
            boolean enabled = (boolean) transition.right().invokeExact(this);

            if (enabled) {
                currentState = stateValues[transition.leftInt()];
                return;
            }
        }
    }

    protected boolean processStateEvents() throws Throwable {
        List<MethodHandle> events = stateEvents[currentState.ordinal()];

        if (events == null || events.isEmpty()) return false;

        boolean keep = false;

        for (MethodHandle event : events) {
            boolean keepThis = (boolean) event.invokeExact(this);
            keep |= keepThis;
        }

        return keep;
    }

    public void onStateChange(Consumer<T> listener) {
        stateChangeListeners.add(listener);
    }

    protected void notifyStateChangeListeners() {
        for (Consumer<T> listener : stateChangeListeners) {
            listener.accept((T) this);
        }
    }

    protected void discoverStateMethods() {
        for (Class<?> type = getClass(); type != FSM.class && type != null; type = type.getSuperclass()) {
            var eventsAndTransitions = EVENTS_AND_TRANSITIONS.get(type);
            var events = eventsAndTransitions.left();
            var transitions = eventsAndTransitions.right();

            for (var event : events) {
                int state = stateOrdinal(event.left());

                if (stateEvents[state] == null) stateEvents[state] = new ArrayList<>();
                stateEvents[state].add(event.right());
            }

            for (var transition : transitions) {
                int fromState = stateOrdinal(transition.left().left());
                int toState = stateOrdinal(transition.left().right());

                if (stateTransitions[fromState] == null) stateTransitions[fromState] = new ArrayList<>();
                stateTransitions[fromState].add(IntReferenceImmutablePair.of(toState, transition.right()));
            }
        }
    }

    protected int stateOrdinal(String name) {
        for (S state : stateValues) {
            if (state.name().equals(name)) return state.ordinal();
        }

        throw new IllegalArgumentException("Unknown FSM state: " + name);
    }

    /**
     * Marks a no-argument {@code boolean} method to run while the FSM is in a state.
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    public @interface Event {

        /** Name of the state in which the method runs. */
        String value();
    }

    /**
     * Marks a no-argument {@code boolean} method that enables a state transition.
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    public @interface Transition {

        /** State from which the transition is checked. */
        String from();

        /** State entered when the method returns {@code true}. */
        String to();
    }
}
