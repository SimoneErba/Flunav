package com.flunav.backend.models.multisimulation;

import java.time.Instant;
import java.util.Map;

public record MultiSimulationReport(
        String multiSimulationId,
        int completedRuns,
        int failedRuns,
        int cancelledRuns,
        Map<String, MetricDistribution> metrics,
        Instant generatedAt) {

    public MultiSimulationReport {
        metrics = metrics == null ? Map.of() : Map.copyOf(metrics);
    }
}
