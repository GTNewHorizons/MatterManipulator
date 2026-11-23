package com.recursive_pineapple.matter_manipulator.common.utils.scheduler;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Sorts this list according to the order induced by the specified Comparator.
 * The sorting is done in-place to the supplied list
 */
public class FSMSort<T> extends FSM<FSMSort.State, FSMSort<T>> {

    public enum State {

        INIT_SORT,
        INIT_PASS,
        INIT_PARTITION,
        PROCESS_PASS,
        COMPLETE_PASS,
        FINISH;

        public static class NAMES {

            public static final String INIT_SORT = "INIT_SORT";
            public static final String INIT_PASS = "INIT_PASS";
            public static final String INIT_PARTITION = "INIT_PARTITION";
            public static final String PROCESS_PASS = "PROCESS_PASS";
            public static final String COMPLETE_PASS = "COMPLETE_PASS";
            public static final String FINISH = "FINISH";
        }
    }

    public List<T> elements;
    public Comparator<T> comparator;

    protected int totalPass = -1;
    protected int pass;

    protected List<T> buffer;

    protected int partition;
    protected int partitionIndex;

    protected int index;
    protected int left;
    protected int right;

    public FSMSort(List<T> elements, Comparator<T> comparator) {
        super(State.INIT_SORT, State.FINISH);

        this.elements = elements;
        this.comparator = comparator;
    }

    @FSM.Event(State.NAMES.INIT_SORT)
    protected boolean initSort() {
        if (elements.size() <= 1) {
            totalPass = 0;
            return true;
        }

        totalPass = 32 - Integer.numberOfLeadingZeros(elements.size() - 1);
        buffer = new ArrayList<>();
        pass = 0;

        return true;
    }

    @FSM.Transition(from = State.NAMES.INIT_SORT, to = State.NAMES.INIT_PASS)
    protected boolean initializedSort() {
        return totalPass > 0 && elements.size() > 1;
    }

    @FSM.Transition(from = State.NAMES.INIT_SORT, to = State.NAMES.FINISH)
    protected boolean finishedInitializedSort() {
        return totalPass == 0 && elements.size() <= 1;
    }

    @FSM.Event(State.NAMES.INIT_PASS)
    protected boolean initPass() {
        index = 0;
        partition = 1 << (pass + 1);
        partitionIndex = 0;

        return true;
    }

    @FSM.Transition(from = State.NAMES.INIT_PASS, to = State.NAMES.INIT_PARTITION)
    protected boolean initializedPass() {
        return index == 0 && partitionIndex == 0 && partition != 0;
    }

    @FSM.Event(State.NAMES.INIT_PARTITION)
    protected boolean initPartition() {
        buffer.clear();

        int toIndex = Math.min(elements.size(), partition * (partitionIndex + 1));
        buffer.addAll(elements.subList(partition * partitionIndex, toIndex));

        left = 0;
        right = 0;

        partitionIndex++;

        return true;
    }

    @FSM.Transition(from = State.NAMES.INIT_PARTITION, to = State.NAMES.PROCESS_PASS)
    protected boolean initializedPartition() {
        return left == 0 && right == 0 && !buffer.isEmpty();
    }

    @FSM.Event(State.NAMES.PROCESS_PASS)
    protected boolean processPass() {
        if (index >= elements.size()) return true;
        if (left + right >= buffer.size()) return true;

        T leftElement = left < partition / 2 ? buffer.get(left) : null;
        T rightElement = right + partition / 2 < buffer.size() ? buffer.get(right + partition / 2) : null;

        if (leftElement != null && rightElement != null) {
            if (comparator.compare(leftElement, rightElement) > 0) {
                right++;
                elements.set(index, rightElement);
            } else {
                left++;
                elements.set(index, leftElement);
            }
        } else if (leftElement != null) {
            left++;
            elements.set(index, leftElement);
        } else if (rightElement != null) {
            right++;
            elements.set(index, rightElement);
        } else {
            return true;
        }

        index++;

        return true;
    }

    @FSM.Transition(from = State.NAMES.PROCESS_PASS, to = State.NAMES.INIT_PARTITION)
    protected boolean nextPartition() {
        return index < elements.size() && left + right >= buffer.size();
    }

    @FSM.Transition(from = State.NAMES.PROCESS_PASS, to = State.NAMES.COMPLETE_PASS)
    protected boolean passProcessed() {
        return index >= elements.size();
    }

    @FSM.Event(State.NAMES.COMPLETE_PASS)
    protected boolean completePass() {
        pass++;
        partition = 0;

        return true;
    }

    @FSM.Transition(from = State.NAMES.COMPLETE_PASS, to = State.NAMES.INIT_PASS)
    protected boolean nextPass() {
        return partition == 0 && pass < totalPass;
    }

    @FSM.Transition(from = State.NAMES.COMPLETE_PASS, to = State.NAMES.FINISH)
    protected boolean finished() {
        return partition == 0 && pass == totalPass;
    }
}
