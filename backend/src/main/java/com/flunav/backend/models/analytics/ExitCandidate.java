package com.flunav.backend.models.analytics;

import java.time.Instant;

public record ExitCandidate(
        String candidateId,
        String exitEventId,
        String itemId,
        String chuteId,
        Instant exitTimestamp,
        String simulationId,
        Instant simulationCreatedAt,
        Instant liveHistoryCutoff) {
}
