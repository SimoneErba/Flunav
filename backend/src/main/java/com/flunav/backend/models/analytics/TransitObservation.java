package com.flunav.backend.models.analytics;

import java.time.Instant;

public record TransitObservation(
        String sourceEventId,
        String itemId,
        String conveyorId,
        String sourceLocationId,
        String targetLocationId,
        long durationMillis,
        Instant timestamp) {
}
