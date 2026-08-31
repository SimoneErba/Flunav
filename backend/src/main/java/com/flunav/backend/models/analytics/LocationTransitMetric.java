package com.flunav.backend.models.analytics;

import java.time.Instant;
import java.util.List;

import flunav.types.PositionType;

public record LocationTransitMetric(
        String sourceEventId,
        Instant timestamp,
        String simulationId,
        String itemId,
        String fromLocationId,
        String toLocationId,
        String fromPositionId,
        PositionType fromPositionType,
        String toPositionId,
        PositionType toPositionType,
        long transitTimeMillis,
        List<String> path) {

    public LocationTransitMetric(Instant timestamp, String simulationId, String itemId, String fromLocationId,
            String toLocationId, String fromPositionId, PositionType fromPositionType, String toPositionId,
            PositionType toPositionType, long transitTimeMillis, List<String> path) {
        this(null, timestamp, simulationId, itemId, fromLocationId, toLocationId, fromPositionId,
                fromPositionType, toPositionId, toPositionType, transitTimeMillis, path);
    }
}
