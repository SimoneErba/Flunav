package com.flunav.backend.services;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** One bounded table queue; callers capture simulation scope and domain time before enqueueing. */
final class ClickHouseBatch<T> {
    private static final Logger logger = LoggerFactory.getLogger(ClickHouseBatch.class);
    private final String table;
    private final int capacity;
    private final int batchSize;
    private final Consumer<List<T>> writer;
    private final ArrayDeque<T> pending = new ArrayDeque<>();

    ClickHouseBatch(String table, int capacity, int batchSize, Consumer<List<T>> writer) {
        if (batchSize <= 0 || batchSize > capacity) {
            throw new IllegalArgumentException("Batch size must be positive and no larger than capacity");
        }
        this.table = table;
        this.capacity = capacity;
        this.batchSize = batchSize;
        this.writer = writer;
    }

    synchronized void enqueue(T value) {
        if (pending.size() >= capacity) {
            throw new IllegalStateException("ClickHouse " + table + " queue reached capacity " + capacity);
        }
        pending.addLast(value);
        if (pending.size() >= batchSize) {
            flush();
        }
    }

    synchronized void flush() {
        try {
            flushOrThrow();
        } catch (RuntimeException e) {
            logger.error("Retained {} queued rows after a ClickHouse {} batch failure", pending.size(), table, e);
        }
    }

    /** Holds the queue lock through insertion so retries and simulation removal cannot race with an in-flight batch. */
    private void flushOrThrow() {
        if (pending.isEmpty()) {
            return;
        }
        List<T> batch = new ArrayList<>(Math.min(batchSize, pending.size()));
        while (!pending.isEmpty() && batch.size() < batchSize) {
            batch.add(pending.removeFirst());
        }
        try {
            writer.accept(batch);
        } catch (RuntimeException e) {
            for (int index = batch.size() - 1; index >= 0; index--) {
                pending.addFirst(batch.get(index));
            }
            throw e;
        }
    }

    synchronized void removeIf(Predicate<T> predicate) {
        pending.removeIf(predicate);
    }

    /** Drains on orderly shutdown, stopping on a failed insert instead of retrying indefinitely. */
    synchronized void drain() {
        while (!pending.isEmpty()) {
            try {
                flushOrThrow();
            } catch (RuntimeException e) {
                logger.error("Unable to drain ClickHouse {}; {} rows remain", table, pending.size(), e);
                break;
            }
        }
    }
}
