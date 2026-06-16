package com.flunav.backend.models.response;

import java.time.Instant;
import java.util.Map;

public record EntityEventRecord(
        String eventId,
        String eventType,
        String entityId,
        Instant timestampReceived,
        Instant timestampProcessed,
        Map<String, Object> payload) {
}
