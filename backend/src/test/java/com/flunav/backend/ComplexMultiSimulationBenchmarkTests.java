package com.flunav.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
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
import com.flunav.backend.models.multisimulation.ConveyorFailureConfiguration;
import com.flunav.backend.models.multisimulation.DestinationProbability;
import com.flunav.backend.models.multisimulation.MultiSimulationConfiguration;
import com.flunav.backend.models.multisimulation.MultiSimulationStatus;
import com.flunav.backend.services.EventProcessor;
import com.flunav.backend.services.MultiSimulationService;

import flunav.events.ConnectionCreatedEvent;
import flunav.events.LocationCreatedEvent;
import flunav.types.ConveyorType;
import flunav.types.LocationType;

@SpringBootTest(properties = {
        "springwolf.enabled=false",
        "state-recovery.enabled=false",
        "graph-snapshot.enabled=false",
        "metric-snapshot.enabled=false",
        "multi-simulation.max-concurrent-runs=${flunav.benchmark.concurrent-runs:20}"
})
@Tag("stress")
@Tag("benchmark")
@EnabledIfSystemProperty(named = "flunav.complex-benchmark", matches = "true")
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class ComplexMultiSimulationBenchmarkTests extends BaseIntegrationTest {
    private static final Logger logger = LoggerFactory.getLogger(ComplexMultiSimulationBenchmarkTests.class);
    private static final int RUN_COUNT = 500;
    private static final long DURATION_SECONDS = Duration.ofHours(2).toSeconds();
    private static final double ARRIVAL_RATE_PER_HOUR = 1_200.0;
    private static final double ARRIVAL_VARIATION_PERCENT = 10.0;
    private static final double FAILURES_PER_HOUR = 0.1;
    private static final long REPAIR_DURATION_SECONDS = 60;
    private static final int CONVEYOR_COUNT = 15;
    private static final Duration COMPLETION_TIMEOUT = Duration.ofMinutes(45);
    private static final Duration PROGRESS_LOG_INTERVAL = Duration.ofSeconds(10);

    private final EventProcessor eventProcessor;
    private final MultiSimulationService multiSimulationService;

    ComplexMultiSimulationBenchmarkTests(
            EventProcessor eventProcessor,
            MultiSimulationService multiSimulationService) {
        this.eventProcessor = eventProcessor;
        this.multiSimulationService = multiSimulationService;
    }

    /** Measures a form-equivalent stochastic experiment with failures and a cyclic topology. */
    @Test
    void fiveHundredRunsAcrossThreeIngressLanesFiveExitsAndOneRecirculationPath() throws Exception {
        String prefix = "complex-benchmark-" + System.nanoTime();
        BenchmarkTopology topology = createTopology(prefix);
        List<DestinationProbability> destinations = topology.exits().stream()
                .map(exit -> new DestinationProbability(exit, 0.2))
                .toList();
        List<ConveyorFailureConfiguration> failures = topology.conveyors().stream()
                .map(conveyor -> new ConveyorFailureConfiguration(
                        conveyor, FAILURES_PER_HOUR, REPAIR_DURATION_SECONDS))
                .toList();

        assertEquals(CONVEYOR_COUNT, topology.conveyors().size());
        var simulation = multiSimulationService.create(new MultiSimulationConfiguration(
                "Complex 500-run stochastic benchmark",
                DURATION_SECONDS,
                RUN_COUNT,
                new ArrivalConfiguration(
                        ARRIVAL_RATE_PER_HOUR, ArrivalDistribution.POISSON, ARRIVAL_VARIATION_PERCENT),
                topology.source(),
                destinations,
                failures,
                1234L,
                Instant.parse("2035-01-01T00:00:00Z")));

        long startedNanos = System.nanoTime();
        logHostMemory("start", 0, 0, 0, startedNanos);
        multiSimulationService.start(simulation.id());
        waitForCompletion(simulation.id(), COMPLETION_TIMEOUT, startedNanos);
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
        long conveyorFailures = runs.stream()
                .filter(run -> run.metrics() != null)
                .mapToLong(run -> run.metrics().conveyorFailureCount())
                .sum();
        long recirculations = runs.stream()
                .filter(run -> run.metrics() != null)
                .mapToLong(run -> run.metrics().recirculationCount())
                .sum();

        double elapsedSeconds = elapsedNanos / 1_000_000_000.0;
        logger.info(
                "COMPLEX_MULTI_SIMULATION_BENCHMARK requestedRuns={} completedRuns={} failedRuns={} status={} "
                        + "arrivalRatePerHour={} arrivalVariationPercent={} generatedItems={} completedItems={} "
                        + "remainingItems={} conveyorFailures={} recirculations={} wallMs={} generatedItemsPerSecond={}",
                RUN_COUNT,
                completed.completedRuns(),
                completed.failedRuns(),
                completed.status(),
                ARRIVAL_RATE_PER_HOUR,
                ARRIVAL_VARIATION_PERCENT,
                generated,
                completedItems,
                remaining,
                conveyorFailures,
                recirculations,
                TimeUnit.NANOSECONDS.toMillis(elapsedNanos),
                String.format(Locale.ROOT, "%.2f", generated / elapsedSeconds));
        logHostMemory("complete", completed.completedRuns(), completed.failedRuns(), elapsedNanos, startedNanos);

        assertEquals(MultiSimulationStatus.COMPLETED, completed.status());
        assertEquals(RUN_COUNT, completed.completedRuns());
        assertEquals(0, completed.failedRuns());
        assertEquals(RUN_COUNT, runs.size());
        assertTrue(generated > 0);
        assertTrue(conveyorFailures > 0);
        assertNotNull(multiSimulationService.report(simulation.id()));
    }

    private BenchmarkTopology createTopology(String prefix) {
        String source = prefix + "-source";
        List<String> ingresses = ids(prefix, "ingress", 3);
        String merge = prefix + "-merge";
        String sorterA = prefix + "-sorter-a";
        String sorterB = prefix + "-sorter-b";
        String recirculation = prefix + "-recirculation";
        List<String> exits = ids(prefix, "exit", 5);

        createLocation(source, "Arrival Source", LocationType.GENERIC, 10_000, 0.0, 0.0);
        for (int index = 0; index < ingresses.size(); index++) {
            createLocation(ingresses.get(index), "Ingress " + (index + 1), LocationType.JUNCTION,
                    10_000, 1.0, index);
        }
        createLocation(merge, "Ingress Merge", LocationType.JUNCTION, 10_000, 2.0, 1.0);
        createLocation(sorterA, "Sorter A", LocationType.DECISION_POINT, 10_000, 3.0, 1.0);
        createLocation(sorterB, "Sorter B", LocationType.DECISION_POINT, 10_000, 4.0, 1.0);
        createLocation(recirculation, "Recirculation", LocationType.JUNCTION, 10_000, 3.0, 2.0);
        for (int index = 0; index < exits.size(); index++) {
            createLocation(exits.get(index), "Exit " + (index + 1), LocationType.CHUTE,
                    10_000, 5.0, index);
        }

        List<String> conveyors = new ArrayList<>();
        for (int index = 0; index < ingresses.size(); index++) {
            conveyors.add(createConveyor(prefix, conveyors.size(), source, ingresses.get(index)));
        }
        for (String ingress : ingresses) {
            conveyors.add(createConveyor(prefix, conveyors.size(), ingress, merge));
        }
        conveyors.add(createConveyor(prefix, conveyors.size(), merge, sorterA));
        conveyors.add(createConveyor(prefix, conveyors.size(), sorterA, exits.get(0)));
        conveyors.add(createConveyor(prefix, conveyors.size(), sorterA, exits.get(1)));
        conveyors.add(createConveyor(prefix, conveyors.size(), sorterA, sorterB));
        conveyors.add(createConveyor(prefix, conveyors.size(), sorterB, exits.get(2)));
        conveyors.add(createConveyor(prefix, conveyors.size(), sorterB, exits.get(3)));
        conveyors.add(createConveyor(prefix, conveyors.size(), sorterB, exits.get(4)));
        conveyors.add(createConveyor(prefix, conveyors.size(), sorterB, recirculation));
        conveyors.add(createConveyor(prefix, conveyors.size(), recirculation, merge));
        return new BenchmarkTopology(source, List.copyOf(exits), List.copyOf(conveyors));
    }

    private List<String> ids(String prefix, String kind, int count) {
        List<String> values = new ArrayList<>(count);
        for (int index = 1; index <= count; index++) {
            values.add(prefix + "-" + kind + "-" + index);
        }
        return values;
    }

    private void createLocation(
            String id,
            String name,
            LocationType type,
            int capacity,
            double x,
            double y) {
        eventProcessor.process(new LocationCreatedEvent(
                id, name, true, x, y, type, capacity, new HashMap<>()), false).join();
    }

    private String createConveyor(String prefix, int index, String source, String target) {
        String id = prefix + "-conveyor-" + (index + 1);
        eventProcessor.process(new ConnectionCreatedEvent(
                id, source, target, 1.0, 10.0, 0.0, null, true,
                "Conveyor " + (index + 1), true, ConveyorType.BELT, 10_000, new HashMap<>()), false).join();
        return id;
    }

    private void waitForCompletion(String id, Duration timeout, long startedNanos) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        long nextProgressLog = System.nanoTime();
        while (System.nanoTime() < deadline) {
            var simulation = multiSimulationService.get(id);
            MultiSimulationStatus status = simulation.status();
            long now = System.nanoTime();
            if (now >= nextProgressLog) {
                logHostMemory("progress", simulation.completedRuns(), simulation.failedRuns(), now - startedNanos,
                        startedNanos);
                nextProgressLog = now + PROGRESS_LOG_INTERVAL.toNanos();
            }
            if (status == MultiSimulationStatus.COMPLETED
                    || status == MultiSimulationStatus.COMPLETED_WITH_FAILURES
                    || status == MultiSimulationStatus.CANCELLED
                    || status == MultiSimulationStatus.FAILED) {
                return;
            }
            Thread.sleep(250);
        }
        throw new AssertionError("Complex multi-simulation benchmark did not finish before " + timeout);
    }

    /** Logs memory from /proc/meminfo so the benchmark reflects the whole host, not only the JVM. */
    private void logHostMemory(String phase, int completedRuns, int failedRuns, long elapsedNanos, long startedNanos) {
        try {
            List<String> lines = Files.readAllLines(Path.of("/proc/meminfo"));
            long totalKiB = memoryValueKiB(lines, "MemTotal:");
            long availableKiB = memoryValueKiB(lines, "MemAvailable:");
            long usedKiB = totalKiB - availableKiB;
            logger.info("COMPLEX_MULTI_SIMULATION_MEMORY phase={} completedRuns={} failedRuns={} elapsedMs={} "
                            + "hostTotalMiB={} hostUsedMiB={} hostAvailableMiB={}",
                    phase, completedRuns, failedRuns, TimeUnit.NANOSECONDS.toMillis(elapsedNanos),
                    toMiB(totalKiB), toMiB(usedKiB), toMiB(availableKiB));
        } catch (IOException | IllegalArgumentException error) {
            logger.warn("Could not read host memory while complex benchmark phase={} elapsedMs={}",
                    phase, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos), error);
        }
    }

    private long memoryValueKiB(List<String> lines, String key) {
        return lines.stream()
                .filter(line -> line.startsWith(key))
                .findFirst()
                .map(line -> line.substring(key.length()).trim().split("\\s+")[0])
                .map(Long::parseLong)
                .orElseThrow(() -> new IllegalArgumentException("Missing " + key + " in /proc/meminfo"));
    }

    private long toMiB(long kibibytes) {
        return kibibytes / 1024;
    }

    private record BenchmarkTopology(String source, List<String> exits, List<String> conveyors) {
    }
}
