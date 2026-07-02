package com.flunav.backend.models.analytics;

import java.time.Instant;
import java.util.List;

import flunav.types.PositionType;

public record LocationTransitMetric(
        Instant timestamp,
        String itemId,
        String fromLocationId,
        String toLocationId,
        String fromPositionId,
        PositionType fromPositionType,
        String toPositionId,
        PositionType toPositionType,
        long transitTimeMillis,
        List<String> path) {
}
