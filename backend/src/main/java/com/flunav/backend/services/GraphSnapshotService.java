package com.flunav.backend.services;

import com.flunav.backend.models.graph.GraphData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "graph-snapshot.enabled", havingValue = "true", matchIfMissing = true)
public class GraphSnapshotService {
    private static final Logger logger = LoggerFactory.getLogger(GraphSnapshotService.class);

    private final GraphService graphService;
    private final ClickHouseService clickHouseService;
    private final TimeService timeService;

    // Use constructor injection for dependencies
    public GraphSnapshotService(GraphService graphService, ClickHouseService clickHouseService, TimeService timeService) {
        this.graphService = graphService;
        this.clickHouseService = clickHouseService;
        this.timeService = timeService;
    }

    /**
     * Periodically takes a snapshot of the current graph state from OrientDB
     * and saves it as a historical record in ClickHouse.
     */
    @Scheduled(initialDelay = 3600000, fixedRate = 3600000) // Runs every hour, starting one hour after boot
    public void takeSnapshot() {
        try {
            logger.info("Starting graph snapshot process...");

            // Step 1: Get the current state of the graph from the graph service.
            GraphData graphState = graphService.getGraphData();

            // Step 2: Create snapshot metadata.
            String snapshotId = UUID.randomUUID().toString();
            Instant timestamp = timeService.now();

            // Step 3: Delegate the saving operation to the ClickHouseService.
            // This cleanly separates the responsibility of data retrieval from data
            // persistence.
            clickHouseService.saveSnapshot(snapshotId, timestamp, graphState);

            logger.info("Graph snapshot completed successfully. Snapshot ID: {}", snapshotId);

        } catch (Exception e) {
            // The service method already logs the detailed error, so we log a higher-level
            // message here.
            logger.error("The scheduled graph snapshot task failed.", e);
        }
    }
}
