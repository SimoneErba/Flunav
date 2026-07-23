package com.flunav.backend.models.response;

import java.time.Instant;

import flunav.types.AlarmSeverity;

public record AlarmHistoryRecord(
        String eventId,
        String alarmId,
        String conveyorId,
        String eventType,
        AlarmSeverity severity,
        String typology,
        boolean stopsConveyor,
        Instant timestamp,
        String simulationId) {
}
