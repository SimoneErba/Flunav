package com.flunav.backend.services;

import com.flunav.backend.context.DatabaseContextHolder;
import com.flunav.backend.domain.Conveyor;
import com.flunav.backend.repositories.LiveConveyorRepository;
import com.flunav.backend.repositories.LiveLocationRepository;
import com.flunav.backend.services.TopologyProvider;
import flunav.types.LocationType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
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
        // In a real multi-tenant scenario, iterate over all active simulation IDs here.
        // For now, we grab the context-aware ID (or 'live' if null).
        captureForSimulation(DatabaseContextHolder.getSimulationId());
    }

    public void captureForSimulation(String simulationId) {
        if (simulationId == null) {
            simulationId = "live"; // Default ID for live system
        }

        Instant now = timeService.now();
        List<Map<String, Object>> metricsBatch = new ArrayList<>();

        // 1. Snapshot Conveyors (Occupancy & Speed)
        for (Conveyor conveyor : topologyProvider.getAllConveyors()) {
            // Get live data from Redis
            Set<String> items = liveConveyorRepo.getItemsOrderedByDistance(conveyor.getId());
            int occupancy = (items != null) ? items.size() : 0;
            double speed = conveyor.getSpeed();

            // Add Occupancy Metric
            metricsBatch.add(createMetricRow(now, simulationId, conveyor.getId(),
                    "CONVEYOR", "OCCUPANCY", occupancy));

            // Add Speed Metric
            metricsBatch.add(createMetricRow(now, simulationId, conveyor.getId(),
                    "CONVEYOR", "SPEED", speed));
        }

        // 2. Snapshot Locations (Occupancy for Chutes/Queues)
        for (var location : topologyProvider.getAllLocations()) {
            // We usually only care about occupancy for Chutes or Stations
            if (location.getType() == LocationType.CHUTE || location.getType() == LocationType.ACCUMULATION) {
                Long count = liveLocationRepo.getItemCount(location.getId());

                metricsBatch.add(createMetricRow(now, simulationId, location.getId(),
                        "LOCATION", "OCCUPANCY", count));
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