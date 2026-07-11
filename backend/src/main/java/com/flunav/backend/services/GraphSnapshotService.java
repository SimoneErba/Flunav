package com.flunav.backend.services;

import com.flunav.backend.models.graph.GraphData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "graph-snapshot.enabled", havingValue = "true", matchIfMissing = true)
public class GraphSnapshotService {
    private static final Logger logger = LoggerFactory.getLogger(GraphSnapshotService.class);

    private final GraphService graphService;
    private final ClickHouseService clickHouseService;
    private final TimeService timeService;
    private final TaskScheduler taskScheduler;
    private final EventProcessor eventProcessor;
    private final Duration snapshotInterval;
    private final AtomicBoolean schedulingStarted = new AtomicBoolean(false);

    public GraphSnapshotService(
            GraphService graphService,
            ClickHouseService clickHouseService,
            TimeService timeService,
            TaskScheduler taskScheduler,
            EventProcessor eventProcessor,
            @Value("${graph-snapshot.interval:5m}") Duration snapshotInterval) {
        this.graphService = graphService;
        this.clickHouseService = clickHouseService;
        this.timeService = timeService;
        this.taskScheduler = taskScheduler;
        this.eventProcessor = eventProcessor;
        this.snapshotInterval = snapshotInterval;
    }

    /**
     * Starts snapshotting only after startup recovery has had a chance to restore
     * live state. The one-shot guard prevents duplicate schedules if the ready event
     * is observed more than once in tests or embedded contexts.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.LOWEST_PRECEDENCE)
    public void takeInitialSnapshotAfterRecovery() {
        if (!schedulingStarted.compareAndSet(false, true)) {
            return;
        }

        takeSnapshot();
        Instant firstScheduledSnapshot = timeService.physicalNow().plus(snapshotInterval);
        taskScheduler.scheduleWithFixedDelay(this::takeSnapshot, firstScheduledSnapshot, snapshotInterval);
        logger.info("Scheduled graph snapshots every {} after startup recovery.", snapshotInterval);
    }

    /**
     * Persists a graph snapshot built from the current domain clock.
     * Snapshots give historical restore a compact baseline so replay only needs the
     * ClickHouse event delta after the snapshot timestamp.
     */
    private void takeSnapshot() {
        try {
            logger.info("Starting graph snapshot process...");

            String snapshotId = UUID.randomUUID().toString();
            eventProcessor.withLiveSnapshotBarrier(() -> {
                clickHouseService.flushAllEventsOrThrow();
                Instant timestamp = timeService.now();
                GraphData graphState = graphService.getGraphData(timestamp, false);
                clickHouseService.saveSnapshot(snapshotId, timestamp, graphState);
                return null;
            });

            logger.info("Graph snapshot completed successfully. Snapshot ID: {}", snapshotId);
        } catch (Exception e) {
            logger.error("The graph snapshot task failed.", e);
        }
    }
}
