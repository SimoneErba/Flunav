package com.flunav.backend.services;

import com.flunav.backend.context.DatabaseContextHolder;
import com.flunav.backend.domain.Conveyor;
import com.flunav.backend.repositories.LiveConveyorRepository;
import com.flunav.backend.repositories.LiveLocationRepository;
import com.flunav.backend.services.TopologyProvider;
import flunav.types.LocationType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@ConditionalOnProperty(name = "metric-snapshot.enabled", havingValue = "true", matchIfMissing = true)
public class MetricSnapshotService {
    private static final Logger logger = LoggerFactory.getLogger(MetricSnapshotService.class);

    private final LiveConveyorRepository liveConveyorRepo;
    private final LiveLocationRepository liveLocationRepo;
    private final TopologyProvider topologyProvider;
    private final ClickHouseService clickHouseService;
    private final TimeService timeService;

    public MetricSnapshotService(LiveConveyorRepository liveConveyorRepo,
            LiveLocationRepository liveLocationRepo,
            TopologyProvider topologyProvider,
            ClickHouseService clickHouseService,
            TimeService timeService) {
        this.liveConveyorRepo = liveConveyorRepo;
        this.liveLocationRepo = liveLocationRepo;
        this.topologyProvider = topologyProvider;
        this.clickHouseService = clickHouseService;
        this.timeService = timeService;
    }

    /**
     * Runs every 10 seconds to capture the heartbeat of the system.
     */
    @Scheduled(fixedRate = 10000)
    public void captureSystemSnapshot() {
        captureAt(null, timeService.physicalNow());
    }

    public void captureForSimulation(String simulationId) {
        captureAt(simulationId, timeService.now());
    }

    /**
     * Captures a deterministic metric boundary supplied by simulation playback.
     * Live scheduling passes physical time, while callers in simulation context pass
     * the virtual boundary.
     */
    public void captureAt(String simulationId, Instant now) {
        String metricScope = simulationId != null ? simulationId : "live";
        List<Map<String, Object>> metricsBatch = new ArrayList<>();

        // 1. Snapshot Conveyors (Occupancy & Speed)
        for (Conveyor conveyor : topologyProvider.getAllConveyors()) {
            // Get live data from Redis
            Set<String> items = liveConveyorRepo.getItemsOrderedByDistance(conveyor.getId());
            int occupancy = (items != null) ? items.size() : 0;
            double speed = conveyor.getSpeed();

            // Add Occupancy Metric
            metricsBatch.add(createMetricRow(now, metricScope, conveyor.getId(),
                    "CONVEYOR", "OCCUPANCY", occupancy));

            // Add Speed Metric
            metricsBatch.add(createMetricRow(now, metricScope, conveyor.getId(),
                    "CONVEYOR", "SPEED", speed));
            metricsBatch.add(createMetricRow(now, metricScope, conveyor.getId(),
                    "CONVEYOR", "STATUS", conveyor.isActive() ? 1 : 0));
        }

        // 2. Snapshot Locations (Occupancy for Chutes/Queues)
        for (var location : topologyProvider.getAllLocations()) {
            // We usually only care about occupancy for Chutes or Stations
            if (location.getType() == LocationType.CHUTE || location.getType() == LocationType.ACCUMULATION) {
                Long count = liveLocationRepo.getItemCount(location.getId());

                metricsBatch.add(createMetricRow(now, metricScope, location.getId(),
                        "LOCATION", "OCCUPANCY", count));
                metricsBatch.add(createMetricRow(now, metricScope, location.getId(),
                        "LOCATION", "STATUS", Boolean.TRUE.equals(location.getActive()) ? 1 : 0));
            }
        }

        // 3. Bulk Insert into ClickHouse using the new JSON method
        if (!metricsBatch.isEmpty()) {
            clickHouseService.saveMetricSnapshots(metricsBatch);
        }
    }

    /**
     * Helper to create the Map structure for ClickHouse JSONEachRow insert.
     */
    private Map<String, Object> createMetricRow(Instant timestamp, String simId, String compId,
            String compType, String metricType, Number value) {
        Map<String, Object> row = new HashMap<>();
        row.put("timestamp", timestamp); // ClickHouseService will format this
        row.put("simulation_id", simId);
        row.put("component_id", compId);
        row.put("component_type", compType); // Matches Enum string in CH
        row.put("metric_type", metricType); // Matches Enum string in CH
        row.put("value", value);
        return row;
    }
}
