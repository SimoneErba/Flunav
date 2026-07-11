package com.flunav.backend.models.analytics;

import java.time.Instant;

public record ConnectionStateSignal(
        String eventId,
        Instant timestamp,
        String simulationId,
        String conveyorId,
        String eventType,
        Boolean active,
        Double speed,
        String sourceId,
        String targetId) {
}
