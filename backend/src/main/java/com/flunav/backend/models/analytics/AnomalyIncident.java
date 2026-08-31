package com.flunav.backend.models.analytics;

import java.time.Instant;
import java.util.List;

public record AnomalyIncident(
        String incidentId,
        String scopeId,
        String simulationId,
        String probableRootComponentId,
        String confidence,
        List<String> findingIds,
        List<String> componentIds,
        Instant firstFindingTimestamp,
        Instant updatedAt) {
}
