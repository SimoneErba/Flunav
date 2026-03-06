package com.flunav.backend.models.analytics;

import java.time.Instant;

public record MetricEvent(
        Instant timestamp,
        String simulationId,
        String componentId,
        String componentType, // "CONVEYOR", "LOCATION"
        String metricType, // "OCCUPANCY", "SPEED", "THROUGHPUT"
        double value) {
}