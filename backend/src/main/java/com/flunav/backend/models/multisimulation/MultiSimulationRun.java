package com.flunav.backend.models.multisimulation;

import java.time.Instant;

public record MultiSimulationRun(
        String multiSimulationId,
        int runIndex,
        long seed,
        MultiSimulationRunStatus status,
        double effectiveArrivalRate,
        Instant startedAt,
        Instant completedAt,
        MultiSimulationRunMetrics metrics,
        String error) {
}
