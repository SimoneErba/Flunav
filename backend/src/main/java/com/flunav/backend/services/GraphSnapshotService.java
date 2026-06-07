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
    private final Duration snapshotInterval;
    private final AtomicBoolean schedulingStarted = new AtomicBoolean(false);

    public GraphSnapshotService(
            GraphService graphService,
            ClickHouseService clickHouseService,
            TimeService timeService,
            TaskScheduler taskScheduler,
            @Value("${graph-snapshot.interval:5m}") Duration snapshotInterval) {
        this.graphService = graphService;
        this.clickHouseService = clickHouseService;
        this.timeService = timeService;
        this.taskScheduler = taskScheduler;
        this.snapshotInterval = snapshotInterval;
    }

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

    private void takeSnapshot() {
        try {
            logger.info("Starting graph snapshot process...");

            GraphData graphState = graphService.getGraphData();
            String snapshotId = UUID.randomUUID().toString();
            Instant timestamp = timeService.now();

            clickHouseService.saveSnapshot(snapshotId, timestamp, graphState);

            logger.info("Graph snapshot completed successfully. Snapshot ID: {}", snapshotId);
        } catch (Exception e) {
            logger.error("The graph snapshot task failed.", e);
        }
    }
}
