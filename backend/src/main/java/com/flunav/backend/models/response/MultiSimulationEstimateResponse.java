package com.flunav.backend.models.response;

public record MultiSimulationEstimateResponse(
        long expectedItemsPerRun,
        long expectedItemsTotal,
        int locationCount,
        int conveyorCount,
        int reachableExitCount,
        int maximumRouteDepth,
        int configuredParallelRuns,
        int parallelRuns,
        long estimatedSeconds,
        long lowerSeconds,
        long upperSeconds) {
}
