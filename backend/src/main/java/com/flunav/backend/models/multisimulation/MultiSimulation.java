package com.flunav.backend.models.multisimulation;

import java.time.Instant;

public record MultiSimulation(
        String id,
        MultiSimulationConfiguration configuration,
        MultiSimulationBaseline baseline,
        long baseSeed,
        MultiSimulationStatus status,
        int completedRuns,
        int failedRuns,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt,
        boolean cancelRequested,
        String error) {
}
