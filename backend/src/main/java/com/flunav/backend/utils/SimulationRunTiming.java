package com.flunav.backend.utils;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/** Collects wall-clock costs on the worker that owns one multi-simulation run. */
public final class SimulationRunTiming implements AutoCloseable {
    private static final ThreadLocal<SimulationRunTiming> CURRENT = new ThreadLocal<>();

    private final SimulationRunTiming previous;
    private final long startedNanos = System.nanoTime();
    private final Map<String, Stat> sections = new HashMap<>();

    private SimulationRunTiming() {
        previous = CURRENT.get();
        CURRENT.set(this);
    }

    public static SimulationRunTiming start() {
        return new SimulationRunTiming();
    }

    public static boolean active() {
        return CURRENT.get() != null;
    }

    public static long tick() {
        return active() ? System.nanoTime() : 0L;
    }

    public static void record(String section, long startedNanos) {
        SimulationRunTiming current = CURRENT.get();
        if (current != null && startedNanos != 0L) {
            current.sections.computeIfAbsent(section, ignored -> new Stat())
                    .add(System.nanoTime() - startedNanos);
        }
    }

    public long elapsedMillis() {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    public String summary() {
        return sections.entrySet().stream()
                .sorted(Map.Entry.comparingByValue(Comparator.comparingLong(Stat::nanos).reversed()))
                .map(entry -> entry.getKey() + "=" + entry.getValue().count() + "x/"
                        + entry.getValue().nanos() / 1_000_000 + "ms")
                .collect(Collectors.joining(", "));
    }

    @Override
    public void close() {
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }

    private static final class Stat {
        private long count;
        private long nanos;

        void add(long elapsedNanos) {
            count++;
            nanos += elapsedNanos;
        }

        long count() {
            return count;
        }

        long nanos() {
            return nanos;
        }
    }
}
