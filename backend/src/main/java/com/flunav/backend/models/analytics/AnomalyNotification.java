package com.flunav.backend.models.analytics;

import java.time.Instant;

public record AnomalyNotification(
        String kind,
        AnomalyFinding finding,
        AnomalyIncident incident,
        String alarmId,
        String componentId,
        Instant virtualTimestamp) {
}
