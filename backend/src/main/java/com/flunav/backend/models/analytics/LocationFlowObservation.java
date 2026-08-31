package com.flunav.backend.models.analytics;

import java.time.Instant;

public record LocationFlowObservation(
        String sourceEventId,
        String itemId,
        String locationId,
        Direction direction,
        Instant timestamp) {
    public enum Direction {
        ARRIVAL,
        DEPARTURE
    }
}
