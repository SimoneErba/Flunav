package com.flunav.backend.services;

import com.flunav.backend.context.DatabaseContextHolder;
import com.flunav.backend.models.response.ThroughputMetric;
import com.flunav.backend.repositories.LiveItemRepository;
import com.flunav.backend.repositories.LiveSimulationRepository;

import flunav.events.ChuteEmptyEvent;
import flunav.events.DomainEvent;
import flunav.events.ItemCreatedEvent;
import flunav.events.ItemExitedEvent;
import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;

@Service
public class ThroughputBucketService {
    private static final Logger logger = LoggerFactory.getLogger(ThroughputBucketService.class);
    public static final int BUCKET_SECONDS = 5;

    private final ClickHouseService clickHouseService;
    private final LiveItemRepository liveItemRepository;
    private final LiveSimulationRepository liveSimulationRepository;
    private final SimulationService simulationService;
    private final TimeService timeService;
    private final WebSocketService webSocketService;
    private final Map<BucketKey, BucketAccumulator> buckets = new HashMap<>();
    private final Map<String, Instant> lastSimulationBucketStarts = new HashMap<>();
    private final Map<String, NavigableMap<Instant, ThroughputMetric>> simulationHistory = new HashMap<>();
    private Instant lastLiveBucketStart;

    public ThroughputBucketService(
            ClickHouseService clickHouseService,
            LiveItemRepository liveItemRepository,
            LiveSimulationRepository liveSimulationRepository,
            @Lazy SimulationService simulationService,
            TimeService timeService,
            WebSocketService webSocketService) {
        this.clickHouseService = clickHouseService;
        this.liveItemRepository = liveItemRepository;
        this.liveSimulationRepository = liveSimulationRepository;
        this.simulationService = simulationService;
        this.timeService = timeService;
        this.webSocketService = webSocketService;
    }

    /**
     * Records ingress and physical egress only after an event has reduced
     * successfully. Administrative item deletion is deliberately excluded because
     * it does not prove that an item crossed a system boundary.
     */
    public synchronized void recordSuccessfulReduction(
            DomainEvent event,
            Map<String, Object> result) {
        if (isIgnoredCreate(event, result)) {
            return;
        }

        long entered = event instanceof ItemCreatedEvent ? 1 : 0;
        long exited = event instanceof ChuteEmptyEvent || event instanceof ItemExitedEvent ? exitedItems(result) : 0;

        if (entered == 0 && exited == 0) {
            return;
        }

        String simulationId = DatabaseContextHolder.getSimulationId();
        Instant bucketStart = bucketStart(event.getTimestamp());
        BucketAccumulator accumulator = buckets.computeIfAbsent(new BucketKey(simulationId, bucketStart),
                ignored -> new BucketAccumulator());
        accumulator.itemsEntered += entered;
        accumulator.itemsExited += exited;
        accumulator.itemsCurrent = countCurrentItems(simulationId);
    }

    public synchronized void flushBuckets() {
        flushBuckets(true);
    }

    @Scheduled(fixedRate = BUCKET_SECONDS * 1000L)
    public synchronized void flushCompletedBuckets() {
        Instant physicalNow = timeService.physicalNow();
        flushBuckets(false, physicalNow);
        emitIdleLiveBucket(physicalNow);
        emitIdleSimulationBuckets();
    }

    private void flushBuckets(boolean includeCurrentBucket) {
        flushBuckets(includeCurrentBucket, timeService.physicalNow());
    }

    /**
     * Flushes event-bearing buckets against physical or simulation progress.
     * Event timestamps define the bucket while the relevant clock decides when the
     * bucket is complete.
     */
    private void flushBuckets(boolean includeCurrentBucket, Instant physicalNow) {
        if (buckets.isEmpty()) {
            return;
        }

        Iterator<Map.Entry<BucketKey, BucketAccumulator>> iterator = buckets.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<BucketKey, BucketAccumulator> entry = iterator.next();
            BucketKey key = entry.getKey();
            Instant progress = key.simulationId() == null
                    ? physicalNow
                    : simulationProgress(key.simulationId(), physicalNow);
            if (!includeCurrentBucket && key.bucketStart().plusSeconds(BUCKET_SECONDS).isAfter(progress)) {
                continue;
            }

            BucketAccumulator accumulator = entry.getValue();
            ThroughputMetric metric = new ThroughputMetric(
                    key.bucketStart(),
                    accumulator.itemsEntered,
                    accumulator.itemsExited,
                    accumulator.itemsCurrent,
                    BUCKET_SECONDS);

            emitMetric(key.simulationId(), metric);
            iterator.remove();
        }
    }

    /**
     * Emits the latest completed live bucket even when no ingress or egress
     * occurred. This prevents the HUD from presenting the previous non-zero rate as
     * if it were current and refreshes the active-item snapshot after cleanup.
     */
    private void emitIdleLiveBucket(Instant physicalNow) {
        Instant latestCompletedBucket = bucketStart(physicalNow).minusSeconds(BUCKET_SECONDS);
        if (latestCompletedBucket.equals(lastLiveBucketStart)
                || buckets.containsKey(new BucketKey(null, latestCompletedBucket))) {
            return;
        }

        emitMetric(null, new ThroughputMetric(
                latestCompletedBucket,
                0,
                0,
                countCurrentItems(null),
                BUCKET_SECONDS));
    }

    private void emitMetric(String simulationId, ThroughputMetric metric) {
        if (simulationId == null) {
            clickHouseService.saveThroughputMetric(metric);
            lastLiveBucketStart = metric.getTimestamp();
        } else {
            lastSimulationBucketStarts.put(simulationId, metric.getTimestamp());
            NavigableMap<Instant, ThroughputMetric> history = simulationHistory.computeIfAbsent(
                    simulationId, ignored -> new TreeMap<>());
            history.put(metric.getTimestamp(), metric);
            while (history.size() > 20_000) {
                history.pollFirstEntry();
            }
        }
        webSocketService.broadcastThroughputMetric(simulationId, metric);
    }

    /**
     * Advances idle simulation metrics with virtual progress. Missing simulations
     * are removed from tracking so destroyed namespaces cannot generate phantom
     * analytics updates.
     */
    private void emitIdleSimulationBuckets() {
        for (String simulationId : new ArrayList<>(lastSimulationBucketStarts.keySet())) {
            Instant progress;
            try {
                progress = simulationService.getSimulationClock(simulationId);
            } catch (Exception e) {
                lastSimulationBucketStarts.remove(simulationId);
                simulationHistory.remove(simulationId);
                continue;
            }

            Instant latestCompletedBucket = bucketStart(progress).minusSeconds(BUCKET_SECONDS);
            if (!latestCompletedBucket.isAfter(lastSimulationBucketStarts.get(simulationId))) {
                continue;
            }

            emitMetric(simulationId, new ThroughputMetric(
                    latestCompletedBucket,
                    0,
                    0,
                    countCurrentItems(simulationId),
                    BUCKET_SECONDS));
        }
    }

    /**
     * Merges immutable live history up to the simulation restore point with
     * isolated metrics produced after restore. This lets reconnecting simulation
     * clients recover missed websocket buckets without writing simulated results to
     * ClickHouse.
     */
    public CompletableFuture<List<ThroughputMetric>> getSimulationHistory(
            String simulationId,
            Instant from,
            Instant to,
            int bucketSeconds) {
        Instant end = to != null ? to : simulationProgress(simulationId, timeService.physicalNow());
        Instant start = from != null ? from : end.minusSeconds(24 * 3600L);
        Instant restoreTimestamp = liveSimulationRepository.getState(simulationId)
                .map(LiveSimulationRepository.SimulationMetadata::timestamp)
                .orElse(end);
        Instant liveEnd = end.isBefore(restoreTimestamp) ? end : restoreTimestamp;

        CompletableFuture<List<ThroughputMetric>> liveHistory = start.isAfter(liveEnd)
                ? CompletableFuture.completedFuture(List.of())
                : clickHouseService.getThroughputHistory(start, liveEnd, bucketSeconds);

        return liveHistory.thenApply(liveMetrics -> {
            List<ThroughputMetric> simulatedMetrics;
            synchronized (this) {
                NavigableMap<Instant, ThroughputMetric> history = simulationHistory.get(simulationId);
                simulatedMetrics = history == null
                        ? List.of()
                        : new ArrayList<>(history.subMap(
                                start, true,
                                end, true).values());
            }
            return mergeHistory(liveMetrics, simulatedMetrics, bucketSeconds);
        });
    }

    public synchronized void cleanupSimulationHistory(String simulationId) {
        buckets.keySet().removeIf(key -> simulationId.equals(key.simulationId()));
        lastSimulationBucketStarts.remove(simulationId);
        simulationHistory.remove(simulationId);
    }

    private List<ThroughputMetric> mergeHistory(
            List<ThroughputMetric> liveMetrics,
            List<ThroughputMetric> simulatedMetrics,
            int bucketSeconds) {
        Map<Instant, HistoryAccumulator> merged = new HashMap<>();
        liveMetrics.forEach(metric -> addHistoryMetric(merged, metric, bucketSeconds));
        simulatedMetrics.forEach(metric -> addHistoryMetric(merged, metric, bucketSeconds));

        return merged.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new ThroughputMetric(
                        entry.getKey(),
                        entry.getValue().itemsEntered,
                        entry.getValue().itemsExited,
                        entry.getValue().itemsCurrent,
                        bucketSeconds))
                .toList();
    }

    private void addHistoryMetric(
            Map<Instant, HistoryAccumulator> merged,
            ThroughputMetric metric,
            int bucketSeconds) {
        Instant outputBucket = bucketStart(metric.getTimestamp(), bucketSeconds);
        HistoryAccumulator accumulator = merged.computeIfAbsent(outputBucket, ignored -> new HistoryAccumulator());
        accumulator.itemsEntered += metric.getItemsEntered();
        accumulator.itemsExited += metric.getItemsExited();
        if (accumulator.latestTimestamp == null || !metric.getTimestamp().isBefore(accumulator.latestTimestamp)) {
            accumulator.latestTimestamp = metric.getTimestamp();
            accumulator.itemsCurrent = metric.getItemsCurrent();
        }
    }

    @PreDestroy
    public void cleanup() {
        flushBuckets();
    }

    private boolean isIgnoredCreate(DomainEvent event, Map<String, Object> result) {
        if (!(event instanceof ItemCreatedEvent) || result == null) {
            return false;
        }
        Object status = result.get("status");
        return status != null && !"CREATED".equals(String.valueOf(status));
    }

    private long exitedItems(Map<String, Object> result) {
        if (result == null) {
            return 0;
        }
        Object value = result.get("itemsExited");
        if (value instanceof Number number) {
            return Math.max(0, number.longValue());
        }
        return 0;
    }

    private long countCurrentItems(String simulationId) {
        if (simulationId == null) {
            return liveItemRepository.countActiveItems();
        }
        try (var ignored = DatabaseContextHolder.enterSimulationContext(simulationId)) {
            return liveItemRepository.countActiveItems();
        } catch (Exception e) {
            logger.warn("Failed to count current items for simulation {}", simulationId, e);
            return 0;
        }
    }

    private Instant simulationProgress(String simulationId, Instant fallback) {
        try {
            return simulationService.getSimulationClock(simulationId);
        } catch (Exception e) {
            logger.debug("Simulation {} has no persisted clock; using physical time for bucket flushing",
                    simulationId);
            return fallback;
        }
    }

    private Instant bucketStart(Instant timestamp) {
        return bucketStart(timestamp, BUCKET_SECONDS);
    }

    private Instant bucketStart(Instant timestamp, int bucketSeconds) {
        long epochMillis = timestamp.toEpochMilli();
        long bucketMillis = bucketSeconds * 1000L;
        return Instant.ofEpochMilli((epochMillis / bucketMillis) * bucketMillis);
    }

    private record BucketKey(String simulationId, Instant bucketStart) {
    }

    private static final class BucketAccumulator {
        private long itemsEntered;
        private long itemsExited;
        private long itemsCurrent;
    }

    private static final class HistoryAccumulator {
        private long itemsEntered;
        private long itemsExited;
        private long itemsCurrent;
        private Instant latestTimestamp;
    }
}
