package com.flunav.backend.models.multisimulation;

import java.util.Map;

public record MultiSimulationRunMetrics(
        long itemsGenerated,
        long itemsCompleted,
        long itemsRemaining,
        double throughputPerHour,
        double averageJourneyTimeSeconds,
        double p50JourneyTimeSeconds,
        double p95JourneyTimeSeconds,
        double p99JourneyTimeSeconds,
        double minimumJourneyTimeSeconds,
        double maximumJourneyTimeSeconds,
        long recirculationCount,
        double recirculationRatePercent,
        long maximumSystemPopulation,
        long conveyorFailureCount,
        Map<String, Long> generatedByDestination,
        Map<String, Long> completedByDestination,
        Map<String, Long> failuresByConveyor,
        Map<String, Double> conveyorDowntimePercent) {

    public MultiSimulationRunMetrics {
        generatedByDestination = generatedByDestination == null ? Map.of() : Map.copyOf(generatedByDestination);
        completedByDestination = completedByDestination == null ? Map.of() : Map.copyOf(completedByDestination);
        failuresByConveyor = failuresByConveyor == null ? Map.of() : Map.copyOf(failuresByConveyor);
        conveyorDowntimePercent = conveyorDowntimePercent == null ? Map.of() : Map.copyOf(conveyorDowntimePercent);
    }
}
