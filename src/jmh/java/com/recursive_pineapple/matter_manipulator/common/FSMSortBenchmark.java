package com.recursive_pineapple.matter_manipulator.common;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import com.recursive_pineapple.matter_manipulator.common.utils.scheduler.FSMSort;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@Fork(2)
@State(Scope.Thread)
public class FSMSortBenchmark {

    @Param({ /*"100", "10000",*/ "100000", "1000000" })
    public int size;

    @Param({ "RANDOM", /*"SORTED", "REVERSE", "DUPLICATES"*/ })
    public String distribution;

    private List<Integer> source;
    private List<Integer> values;

    @Setup(Level.Trial)
    public void createSource() {
        Random random = new Random(0x5EED);
        source = new ArrayList<>(size);

        for (int i = 0; i < size; i++) {
            int value;
            switch (distribution) {
                case "SORTED":
                    value = i;
                    break;
                case "REVERSE":
                    value = size - i;
                    break;
                case "DUPLICATES":
                    value = random.nextInt(32);
                    break;
                default:
                    value = random.nextInt();
                    break;
            }
            source.add(value);
        }

        if ("RANDOM".equals(distribution) || "DUPLICATES".equals(distribution)) {
            Collections.shuffle(source, random);
        }
    }

    @Setup(Level.Invocation)
    public void copyInput() {
        values = new ArrayList<>(source);
    }

    @Benchmark
    public List<Integer> listSort() {
        values.sort(Comparator.naturalOrder());
        return values;
    }

    @Benchmark
    public List<Integer> fsmSort() throws Throwable {
        FSMSort<Integer> sort = new FSMSort<>(values, Comparator.naturalOrder());
        while (sort.hasWork()) {
            sort.doWork(1);
        }
        return values;
    }
}
