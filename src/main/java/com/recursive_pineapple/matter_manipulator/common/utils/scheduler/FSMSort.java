package com.recursive_pineapple.matter_manipulator.common.utils.scheduler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Sorts this list according to the order induced by the specified Comparator.
 * The sorting is done in-place to the supplied list
 */
public class FSMSort<T> extends FSM<FSMSort.State, FSMSort<T>> {

    public enum State {

        INIT_SORT,
        SMALL_RUN,
        INIT_PASS,
        PROCESS_PASS,
        COMPLETE_PASS,
        COMPLETE_SORT,
        FINISH;

        public static class NAMES {

            public static final String INIT_SORT = "INIT_SORT";
            public static final String SMALL_RUN = "SMALL_RUN";
            public static final String INIT_PASS = "INIT_PASS";
            public static final String PROCESS_PASS = "PROCESS_PASS";
            public static final String COMPLETE_PASS = "COMPLETE_PASS";
            public static final String COMPLETE_SORT = "COMPLETE_SORT";
            public static final String FINISH = "FINISH";
        }
    }

    public static final int BATCH_SIZE = 16_384;
    public static final int SMALL_RUN = 4;

    public final List<T> elements;
    public final Comparator<T> comparator;

    protected int totalPass = -1;
    protected int pass;

    protected List<T> source;
    protected List<T> dest;

    protected int index;
    protected int partitionSize;
    protected int partitionIndex;

    protected int actualPartitionSize;
    protected int partitionOffset;
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

        totalPass = 32 - SMALL_RUN - Integer.numberOfLeadingZeros(elements.size() - 1);
        totalPass = Math.max(0, totalPass);
        pass = 0;
        index = 0;

        // We'll swap them in initPass
        source = totalPass > 0 ? new ArrayList<>(Collections.nCopies(elements.size(), null)) : null;
        dest = elements;

        return true;
    }

    @FSM.Transition(from = State.NAMES.INIT_SORT, to = State.NAMES.SMALL_RUN)
    protected boolean initializedSort() {
        return totalPass >= 0 && elements.size() > 1;
    }

    @FSM.Transition(from = State.NAMES.INIT_SORT, to = State.NAMES.FINISH)
    protected boolean finishedInitializedSort() {
        return totalPass == 0 && elements.size() <= 1;
    }

    @FSM.Event(State.NAMES.SMALL_RUN)
    protected boolean smallRun() {
        int smallRunSize = (int) Math.pow(2, SMALL_RUN);

        for (int i = 0; i < BATCH_SIZE; i += smallRunSize) {
            if (index >= elements.size()) break;

            int runEnd = Math.min(index + smallRunSize, elements.size());

            // Does insertion sort for small runs
            for (int j = index + 1; j < runEnd; j++) {
                T element = elements.get(j);
                int k = j - 1;

                while (k >= index && comparator.compare(elements.get(k), element) > 0) {
                    elements.set(k + 1, elements.get(k));
                    k--;
                }

                elements.set(k + 1, element);
            }

            index = runEnd;
        }

        return true;
    }

    @FSM.Transition(from = State.NAMES.SMALL_RUN, to = State.NAMES.INIT_PASS)
    protected boolean completedSmallRun() {
        return index >= elements.size() && totalPass > 0;
    }

    @FSM.Transition(from = State.NAMES.SMALL_RUN, to = State.NAMES.FINISH)
    protected boolean finishedSmallRun() {
        return index >= elements.size() && totalPass == 0;
    }

    @FSM.Event(State.NAMES.INIT_PASS)
    protected boolean initPass() {
        index = 0;
        partitionSize = 1 << (pass + SMALL_RUN);
        partitionIndex = 0;

        actualPartitionSize = 0;
        partitionOffset = 0;
        left = 0;
        right = 0;

        List<T> dest = this.dest;
        this.dest = source;
        source = dest;

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

            if (left + right >= actualPartitionSize) {
                partitionOffset = partitionSize * 2 * partitionIndex;

                actualPartitionSize = partitionSize * 2 * (partitionIndex + 1);
                actualPartitionSize = Math.min(actualPartitionSize, elements.size());
                actualPartitionSize -= partitionOffset;

                left = 0;
                right = 0;

                partitionIndex++;

            }

            boolean hasLeft = left < partitionSize;
            boolean hasRight = right + partitionSize < actualPartitionSize;

            if (hasLeft && hasRight) {
                T leftElement = source.get(left + partitionOffset);
                T rightElement = source.get(right + partitionSize + partitionOffset);

                if (comparator.compare(leftElement, rightElement) > 0) {
                    dest.set(index, rightElement);
                    right++;
                } else {
                    dest.set(index, leftElement);
                    left++;
                }
            } else if (hasLeft) {
                dest.set(index, source.get(left + partitionOffset));
                left++;
            } else if (hasRight) {
                dest.set(index, source.get(right + partitionSize + partitionOffset));
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

    @FSM.Transition(from = State.NAMES.COMPLETE_PASS, to = State.NAMES.COMPLETE_SORT)
    protected boolean completed() {
        return partitionSize == 0 && pass == totalPass;
    }

    @FSM.Event(State.NAMES.COMPLETE_SORT)
    protected boolean completeSort() {
        if (elements != dest) {
            Collections.copy(elements, dest);
        }
        dest = null;
        return true;
    }

    @FSM.Transition(from = State.NAMES.COMPLETE_SORT, to = State.NAMES.FINISH)
    protected boolean finished() {
        return dest == null;
    }
}
