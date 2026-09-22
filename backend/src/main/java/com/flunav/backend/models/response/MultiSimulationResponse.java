package com.flunav.backend.models.response;

import java.time.Instant;

import com.flunav.backend.models.multisimulation.MultiSimulation;
import com.flunav.backend.models.multisimulation.MultiSimulationConfiguration;
import com.flunav.backend.models.multisimulation.MultiSimulationStatus;

public record MultiSimulationResponse(
        String id,
        MultiSimulationConfiguration configuration,
        long baseSeed,
        MultiSimulationStatus status,
        int completedRuns,
        int failedRuns,
        int totalRuns,
        String topologyVersion,
        String configurationVersion,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt,
        boolean cancelRequested,
        String error) {

    public static MultiSimulationResponse from(MultiSimulation simulation) {
        return new MultiSimulationResponse(
                simulation.id(),
                simulation.configuration(),
                simulation.baseSeed(),
                simulation.status(),
                simulation.completedRuns(),
                simulation.failedRuns(),
                simulation.configuration().numberOfRuns(),
                simulation.baseline().topologyVersion(),
                simulation.baseline().configurationVersion(),
                simulation.createdAt(),
                simulation.startedAt(),
                simulation.completedAt(),
                simulation.cancelRequested(),
                simulation.error());
    }
}
