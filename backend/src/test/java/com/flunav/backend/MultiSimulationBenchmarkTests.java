package com.flunav.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestConstructor;

import com.flunav.backend.models.multisimulation.ArrivalConfiguration;
import com.flunav.backend.models.multisimulation.ArrivalDistribution;
import com.flunav.backend.models.multisimulation.DestinationProbability;
import com.flunav.backend.models.multisimulation.MultiSimulationConfiguration;
import com.flunav.backend.models.multisimulation.MultiSimulationStatus;
import com.flunav.backend.services.EventProcessor;
import com.flunav.backend.services.MultiSimulationService;

import flunav.events.ConnectionCreatedEvent;
import flunav.events.DestinationExitMappingRecord;
import flunav.events.LocationCreatedEvent;
import flunav.events.MapDestinationExitsEvent;
import flunav.types.ConveyorType;
import flunav.types.LocationType;

@SpringBootTest(properties = {
        "springwolf.enabled=false",
        "state-recovery.enabled=false",
        "graph-snapshot.enabled=false",
        "metric-snapshot.enabled=false",
        "multi-simulation.max-concurrent-runs=4"
})
@Tag("stress")
@Tag("benchmark")
@EnabledIfSystemProperty(named = "flunav.benchmark", matches = "true")
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class MultiSimulationBenchmarkTests extends BaseIntegrationTest {
    private static final Logger logger = LoggerFactory.getLogger(MultiSimulationBenchmarkTests.class);
    private static final int RUN_COUNT = 100;
    private static final long DURATION_SECONDS = Duration.ofHours(2).toSeconds();
    private static final double ARRIVAL_RATE_PER_HOUR = 1_000.0;
    private static final long GENERATED_ITEMS_PER_RUN = 2_000;
    private static final long COMPLETED_ITEMS_PER_RUN = 1_999;
    private static final long TOTAL_GENERATED_ITEMS = RUN_COUNT * GENERATED_ITEMS_PER_RUN;
    private static final long TOTAL_COMPLETED_ITEMS = RUN_COUNT * COMPLETED_ITEMS_PER_RUN;
    private static final Duration COMPLETION_TIMEOUT = Duration.ofMinutes(30);

    private final EventProcessor eventProcessor;
    private final MultiSimulationService multiSimulationService;

    MultiSimulationBenchmarkTests(
            EventProcessor eventProcessor,
            MultiSimulationService multiSimulationService) {
        this.eventProcessor = eventProcessor;
        this.multiSimulationService = multiSimulationService;
    }

    /** Measures the complete runner path after the topology baseline has been captured. */
    @Test
    void oneHundredRunsAtOneThousandItemsPerHourForTwoHours() throws Exception {
        String suffix = String.valueOf(System.nanoTime());
        String source = "benchmark-source-" + suffix;
        String chute = "benchmark-chute-" + suffix;
        String conveyor = "benchmark-conveyor-" + suffix;
        String destination = "benchmark-destination-" + suffix;
        createTopology(source, chute, conveyor, destination);

        var simulation = multiSimulationService.create(new MultiSimulationConfiguration(
                "One-hundred-run 1000 items/hour benchmark",
                DURATION_SECONDS,
                RUN_COUNT,
                new ArrivalConfiguration(ARRIVAL_RATE_PER_HOUR, ArrivalDistribution.FIXED, 0),
                source,
                List.of(new DestinationProbability(destination, 1.0)),
                List.of(),
                1234L,
                Instant.parse("2035-01-01T00:00:00Z")));

        long startedNanos = System.nanoTime();
        multiSimulationService.start(simulation.id());
        waitForCompletion(simulation.id(), COMPLETION_TIMEOUT);
        long elapsedNanos = System.nanoTime() - startedNanos;

        var completed = multiSimulationService.get(simulation.id());
        var runs = multiSimulationService.runs(simulation.id());
        long generated = runs.stream()
                .filter(run -> run.metrics() != null)
                .mapToLong(run -> run.metrics().itemsGenerated())
                .sum();
        long completedItems = runs.stream()
                .filter(run -> run.metrics() != null)
                .mapToLong(run -> run.metrics().itemsCompleted())
                .sum();
        long remaining = runs.stream()
                .filter(run -> run.metrics() != null)
                .mapToLong(run -> run.metrics().itemsRemaining())
                .sum();

        double elapsedSeconds = elapsedNanos / 1_000_000_000.0;
        double itemsPerSecond = completedItems / elapsedSeconds;
        logger.info(
                "MULTI_SIMULATION_BENCHMARK requestedRuns={} completedRuns={} failedRuns={} status={} "
                        + "virtualHoursPerRun=2 ratePerHour={} generatedItemsPerRun={} generatedItems={} "
                        + "completedItems={} remainingItems={} "
                        + "wallMs={} completedItemsPerSecond={}",
                RUN_COUNT,
                completed.completedRuns(),
                completed.failedRuns(),
                completed.status(),
                ARRIVAL_RATE_PER_HOUR,
                GENERATED_ITEMS_PER_RUN,
                generated,
                completedItems,
                remaining,
                TimeUnit.NANOSECONDS.toMillis(elapsedNanos),
                String.format(java.util.Locale.ROOT, "%.2f", itemsPerSecond));

        assertEquals(MultiSimulationStatus.COMPLETED, completed.status());
        assertEquals(RUN_COUNT, completed.completedRuns());
        assertEquals(0, completed.failedRuns());
        assertEquals(RUN_COUNT, runs.size());
        assertNotNull(multiSimulationService.report(simulation.id()));
        assertEquals(TOTAL_GENERATED_ITEMS, generated);
        assertEquals(TOTAL_COMPLETED_ITEMS, completedItems);
        assertEquals(RUN_COUNT, remaining);
    }

    private void createTopology(String source, String chute, String conveyor, String destination) {
        eventProcessor.process(new LocationCreatedEvent(
                source, "Benchmark Source", true, 0.0, 0.0, LocationType.GENERIC, 100, new HashMap<>()),
                false).join();
        eventProcessor.process(new LocationCreatedEvent(
                chute, "Benchmark Chute", true, 1.0, 0.0, LocationType.CHUTE, 100, new HashMap<>()),
                false).join();
        eventProcessor.process(new ConnectionCreatedEvent(
                conveyor, source, chute, 1.0, 1.0, 0.0, null, true,
                "Benchmark Conveyor", true, ConveyorType.BELT, 100, new HashMap<>()), false).join();
        eventProcessor.process(new MapDestinationExitsEvent(List.of(
                new DestinationExitMappingRecord(destination, List.of(chute)))), false).join();
    }

    private void waitForCompletion(String id, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            MultiSimulationStatus status = multiSimulationService.get(id).status();
            if (status == MultiSimulationStatus.COMPLETED
                    || status == MultiSimulationStatus.COMPLETED_WITH_FAILURES
                    || status == MultiSimulationStatus.CANCELLED
                    || status == MultiSimulationStatus.FAILED) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Multi-simulation benchmark did not finish before " + timeout);
    }
}
