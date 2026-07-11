package com.flunav.backend.models.response;

public record JourneySummary(
        long completedItemCount,
        double averageTraversalMillis,
        long minimumTraversalMillis,
        long maximumTraversalMillis,
        long p50TraversalMillis,
        long p90TraversalMillis,
        long p95TraversalMillis,
        long p99TraversalMillis,
        long recirculationEventCount,
        long completedJourneysWithRecirculation,
        double completedJourneysWithRecirculationPercentage) {

    public static JourneySummary empty() {
        return new JourneySummary(0, 0.0, 0, 0, 0, 0, 0, 0, 0, 0, 0.0);
    }
}
