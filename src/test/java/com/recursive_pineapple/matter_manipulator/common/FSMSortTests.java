package com.recursive_pineapple.matter_manipulator.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import com.recursive_pineapple.matter_manipulator.common.utils.scheduler.FSMSort;
import org.junit.jupiter.api.Test;

class FSMSortTests {

    @Test
    void emptyAndSingleElementLists() throws Throwable {
        assertSortedAndPreserved(new ArrayList<>());
        assertSortedAndPreserved(Arrays.asList(42));
    }

    @Test
    void exhaustivelyChecksAllListsOverThreeValuesThroughLengthSeven() throws Throwable {
        for (int length = 1; length <= 7; length++) {
            testProducts(length, 3, new int[length], 0);
        }
    }

    @Test
    void exhaustivelyChecksDistinctPermutationsThroughLengthEight() throws Throwable {
        for (int length = 1; length <= 8; length++) {
            testPermutations(length, new ArrayList<>(), length);
        }
    }

    @Test
    void checksSeededRandomLists() throws Throwable {
        Random random = new Random(0x5EED);

        for (int size = 1; size <= 100; size++) {
            for (int sample = 0; sample < 100; sample++) {
                List<Integer> input = new ArrayList<>();
                for (int i = 0; i < size; i++) {
                    input.add(random.nextInt(21) - 10);
                }

                assertSortedAndPreserved(input);
            }
        }
    }

    @Test
    void checksKnownCounterexamples() throws Throwable {
        assertSortedAndPreserved(Arrays.asList(2, 3, 1));
        assertSortedAndPreserved(Arrays.asList(2, 0));
        assertSortedAndPreserved(Arrays.asList(5, 4, 3, 2, 1));
        assertSortedAndPreserved(Arrays.asList(6, 5, 4, 3, 2, 1));
    }

    @Test
    void processesOneIndexPerWorkCallAndCanBePaused() throws Throwable {
        List<Integer> input = new ArrayList<>(Arrays.asList(3, 1, 2));
        FSMSort<Integer> sort = new FSMSort<>(input, Comparator.naturalOrder());

        sort.pause();
        assertTrue(sort.doWork(1) == false);

        sort.resume();
        while (sort.hasWork()) {
            sort.doWork(1);
        }

        assertEquals(Arrays.asList(1, 2, 3), input);
        assertSame(FSMSort.State.FINISH, sort.getCurrentState());
    }

    @Test
    void equalElementsAreStable() throws Throwable {
        List<TaggedInteger> input = new ArrayList<>(Arrays.asList(
            new TaggedInteger(0, "0a"),
            new TaggedInteger(1, "1b"),
            new TaggedInteger(0, "0c"),
            new TaggedInteger(0, "0d"),
            new TaggedInteger(0, "0e"),
            new TaggedInteger(1, "1f"),
            new TaggedInteger(1, "1g")
        ));

        FSMSort<TaggedInteger> sort = new FSMSort<>(
            input,
            Comparator.comparingInt(value -> value.value)
        );

        while (sort.hasWork()) {
            sort.doWork(1);
        }

        List<String> expectedOrder = Arrays.asList("0a", "0c", "0d", "0e", "1b", "1f", "1g");
        List<String> actualOrder = new ArrayList<>();
        for (TaggedInteger value : input) {
            actualOrder.add(value.tag);
        }

        assertEquals(expectedOrder, actualOrder);
    }

    private void testProducts(int length, int valueCount, int[] values, int index) throws Throwable {
        if (index == length) {
            List<Integer> input = new ArrayList<>();
            for (int value : values) {
                input.add(value);
            }
            assertSortedAndPreserved(input);
            return;
        }

        for (int value = 0; value < valueCount; value++) {
            values[index] = value;
            testProducts(length, valueCount, values, index + 1);
        }
    }

    private void testPermutations(int length, List<Integer> used, int remaining) throws Throwable {
        if (used.size() == length) {
            assertSortedAndPreserved(new ArrayList<>(used));
            return;
        }

        for (int value = 0; value < length; value++) {
            if (!used.contains(value)) {
                used.add(value);
                testPermutations(length, used, remaining - 1);
                used.remove(used.size() - 1);
            }
        }
    }

    private void assertSortedAndPreserved(List<Integer> input) throws Throwable {
        List<Integer> original = new ArrayList<>(input);
        List<Integer> expected = new ArrayList<>(input);
        expected.sort(Comparator.naturalOrder());

        List<Integer> actual = new ArrayList<>(input);
        FSMSort<Integer> sort = new FSMSort<>(actual, Comparator.naturalOrder());

        while (sort.hasWork()) {
            sort.doWork(1);
        }

        assertEquals(expected, actual,
            "sorted result mismatch for input " + original + "; got " + actual);

        Map<Integer, Integer> originalCounts = valueCounts(original);
        Map<Integer, Integer> actualCounts = valueCounts(actual);
        assertEquals(originalCounts, actualCounts,
            "element preservation mismatch for input " + original + "; got " + actual);
    }

    private Map<Integer, Integer> valueCounts(List<Integer> values) {
        Map<Integer, Integer> counts = new HashMap<>();
        for (int value : values) {
            counts.merge(value, 1, Integer::sum);
        }
        return counts;
    }

    private static final class TaggedInteger {

        private final int value;
        private final String tag;

        private TaggedInteger(int value, String tag) {
            this.value = value;
            this.tag = tag;
        }
    }
}
