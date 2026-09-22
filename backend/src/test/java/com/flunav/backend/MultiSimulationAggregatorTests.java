package com.flunav.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.flunav.backend.models.multisimulation.MultiSimulationRun;
import com.flunav.backend.models.multisimulation.MultiSimulationRunMetrics;
import com.flunav.backend.models.multisimulation.MultiSimulationRunStatus;
import com.flunav.backend.services.MultiSimulationAggregator;

class MultiSimulationAggregatorTests {
    private final MultiSimulationAggregator aggregator = new MultiSimulationAggregator();

    @Test
    void knownValuesProduceExactSummaryStatistics() {
        var distribution = aggregator.distribution(List.of(10.0, 20.0, 30.0, 40.0, 50.0));

        assertEquals(30.0, distribution.mean());
        assertEquals(30.0, distribution.median());
        assertEquals(Math.sqrt(200.0), distribution.standardDeviation());
        assertEquals(10.0, distribution.minimum());
        assertEquals(50.0, distribution.maximum());
        assertEquals(12.0, distribution.p5());
        assertEquals(20.0, distribution.p25());
        assertEquals(40.0, distribution.p75());
        assertEquals(48.0, distribution.p95());
    }

    @Test
    void failedRunsAreCountedButExcludedFromMetricDistributions() {
        MultiSimulationRun completed = new MultiSimulationRun(
                "multi", 0, 1, MultiSimulationRunStatus.COMPLETED, 100,
                Instant.EPOCH, Instant.EPOCH, metrics(25), null);
        MultiSimulationRun failed = new MultiSimulationRun(
                "multi", 1, 2, MultiSimulationRunStatus.FAILED, 100,
                Instant.EPOCH, Instant.EPOCH, null, "failure");

        var report = aggregator.aggregate("multi", List.of(completed, failed), Instant.EPOCH);

        assertEquals(1, report.completedRuns());
        assertEquals(1, report.failedRuns());
        assertEquals(1, report.metrics().get("throughputPerHour").sampleCount());
        assertEquals(25, report.metrics().get("throughputPerHour").mean());
    }

    private MultiSimulationRunMetrics metrics(double throughput) {
        return new MultiSimulationRunMetrics(
                10, 5, 5, throughput, 1, 1, 1, 1, 1, 1,
                0, 0, 5, 0, Map.of("A", 10L), Map.of("A", 5L), Map.of(), Map.of());
    }
}
