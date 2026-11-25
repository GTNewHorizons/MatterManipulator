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
        PROCESS_PASS,
        COMPLETE_PASS,
        FINISH;

        public static class NAMES {

            public static final String INIT_SORT = "INIT_SORT";
            public static final String INIT_PASS = "INIT_PASS";
            public static final String PROCESS_PASS = "PROCESS_PASS";
            public static final String COMPLETE_PASS = "COMPLETE_PASS";
            public static final String FINISH = "FINISH";
        }
    }

    public static final int BATCH_SIZE = 2_048;

    public final List<T> elements;
    public final Comparator<T> comparator;

    protected int totalPass = -1;
    protected int pass;

    protected List<T> buffer;
    protected List<T> partition;

    protected int partitionSize;
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
        buffer = new ArrayList<>(elements.size());
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
        left = 0;
        right = 0;

        partition = null;
        partitionSize = 1 << pass;
        partitionIndex = 0;

        buffer.clear();
        buffer.addAll(elements);

        return true;
    }

    @FSM.Transition(from = State.NAMES.INIT_PASS, to = State.NAMES.PROCESS_PASS)
    protected boolean initializedPass() {
        return index == 0 && partitionIndex == 0 && partitionSize != 0;
    }

    @FSM.Event(State.NAMES.PROCESS_PASS)
    protected boolean processPass() {
        for (int i = 0; i < BATCH_SIZE; i++) {
            if (index >= elements.size()) return true;

            if (partition == null || left + right >= partition.size()) {
                int partitionStartIndex = partitionSize * 2 * partitionIndex;
                int partitionEndIndex = partitionSize * 2 * (partitionIndex + 1);
                partitionEndIndex = Math.min(partitionEndIndex, elements.size());

                partition = buffer.subList(partitionStartIndex, partitionEndIndex);
                partitionIndex++;

                left = 0;
                right = 0;
            }

            boolean hasLeft = left < partitionSize;
            boolean hasRight = right + partitionSize < partition.size();

            if (hasLeft && hasRight) {
                T leftElement = partition.get(left);
                T rightElement = partition.get(right + partitionSize);

                if (comparator.compare(leftElement, rightElement) > 0) {
                    elements.set(index, rightElement);
                    right++;
                } else {
                    elements.set(index, leftElement);
                    left++;
                }
            } else if (hasLeft) {
                elements.set(index, partition.get(left));
                left++;
            } else if (hasRight) {
                elements.set(index, partition.get(right + partitionSize));
                right++;
            }

            index++;
        }

        return true;
    }

    @FSM.Transition(from = State.NAMES.PROCESS_PASS, to = State.NAMES.COMPLETE_PASS)
    protected boolean passProcessed() {
        return index >= elements.size();
    }

    @FSM.Event(State.NAMES.COMPLETE_PASS)
    protected boolean completePass() {
        pass++;
        partitionSize = 0;

        return true;
    }

    @FSM.Transition(from = State.NAMES.COMPLETE_PASS, to = State.NAMES.INIT_PASS)
    protected boolean nextPass() {
        return partitionSize == 0 && pass < totalPass;
    }

    @FSM.Transition(from = State.NAMES.COMPLETE_PASS, to = State.NAMES.FINISH)
    protected boolean finished() {
        return partitionSize == 0 && pass == totalPass;
    }
}
