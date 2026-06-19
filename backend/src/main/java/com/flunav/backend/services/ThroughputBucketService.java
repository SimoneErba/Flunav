package com.flunav.backend.services;

import com.flunav.backend.context.DatabaseContextHolder;
import com.flunav.backend.models.response.ThroughputMetric;
import com.flunav.backend.repositories.LiveItemRepository;

import flunav.events.ChuteEmptyEvent;
import flunav.events.DomainEvent;
import flunav.events.ItemCreatedEvent;
import flunav.events.ItemDeletedEvent;
import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

@Service
public class ThroughputBucketService {
    private static final Logger logger = LoggerFactory.getLogger(ThroughputBucketService.class);
    public static final int BUCKET_SECONDS = 5;

    private final ClickHouseService clickHouseService;
    private final LiveItemRepository liveItemRepository;
    private final WebSocketService webSocketService;
    private final Map<BucketKey, BucketAccumulator> buckets = new HashMap<>();

    public ThroughputBucketService(
            ClickHouseService clickHouseService,
            LiveItemRepository liveItemRepository,
            WebSocketService webSocketService) {
        this.clickHouseService = clickHouseService;
        this.liveItemRepository = liveItemRepository;
        this.webSocketService = webSocketService;
    }

    /**
     * Records throughput deltas only after an event has reduced successfully.
     * Chute emptying passes the pre-reduction occupant count because that state is
     * intentionally deleted by the reducer before analytics are emitted.
     */
    public synchronized void recordSuccessfulReduction(
            DomainEvent event,
            Map<String, Object> result,
            long chuteItemsExited) {
        if (isIgnoredCreate(event, result)) {
            return;
        }

        long entered = event instanceof ItemCreatedEvent ? 1 : 0;
        long exited = event instanceof ItemDeletedEvent ? 1 : 0;
        if (event instanceof ChuteEmptyEvent) {
            exited += Math.max(0, chuteItemsExited);
        }

        if (entered == 0 && exited == 0) {
            return;
        }

        String simulationId = DatabaseContextHolder.getSimulationId();
        Instant bucketStart = bucketStart(event.getTimestamp());
        BucketAccumulator accumulator = buckets.computeIfAbsent(new BucketKey(simulationId, bucketStart),
                ignored -> new BucketAccumulator());
        accumulator.itemsEntered += entered;
        accumulator.itemsExited += exited;
    }

    public synchronized void flushBuckets() {
        flushBuckets(true);
    }

    @Scheduled(fixedRate = BUCKET_SECONDS * 1000L)
    public synchronized void flushCompletedBuckets() {
        flushBuckets(false);
    }

    private void flushBuckets(boolean includeCurrentBucket) {
        if (buckets.isEmpty()) {
            return;
        }

        Instant now = Instant.now();
        Iterator<Map.Entry<BucketKey, BucketAccumulator>> iterator = buckets.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<BucketKey, BucketAccumulator> entry = iterator.next();
            BucketKey key = entry.getKey();
            if (!includeCurrentBucket && key.bucketStart().plusSeconds(BUCKET_SECONDS).isAfter(now)) {
                continue;
            }

            BucketAccumulator accumulator = entry.getValue();
            ThroughputMetric metric = new ThroughputMetric(
                    key.bucketStart(),
                    accumulator.itemsEntered,
                    accumulator.itemsExited,
                    countCurrentItems(key.simulationId()),
                    BUCKET_SECONDS);

            if (key.simulationId() == null) {
                clickHouseService.saveThroughputMetric(metric);
            }
            webSocketService.broadcastThroughputMetric(key.simulationId(), metric);
            iterator.remove();
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

    private Instant bucketStart(Instant timestamp) {
        long epochMillis = timestamp.toEpochMilli();
        long bucketMillis = BUCKET_SECONDS * 1000L;
        return Instant.ofEpochMilli((epochMillis / bucketMillis) * bucketMillis);
    }

    private record BucketKey(String simulationId, Instant bucketStart) {
    }

    private static final class BucketAccumulator {
        private long itemsEntered;
        private long itemsExited;
    }
}
