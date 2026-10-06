package com.flunav.backend;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flunav.backend.context.DatabaseContextHolder;
import com.flunav.backend.context.SimulationBuildCacheContext;
import com.flunav.backend.models.RedisLiveItem;
import com.flunav.backend.models.graph.GraphData;
import com.flunav.backend.models.multisimulation.ArrivalConfiguration;
import com.flunav.backend.models.multisimulation.ArrivalDistribution;
import com.flunav.backend.models.multisimulation.DestinationProbability;
import com.flunav.backend.models.multisimulation.MultiSimulationBaseline;
import com.flunav.backend.models.multisimulation.MultiSimulationConfiguration;
import com.flunav.backend.models.multisimulation.MultiSimulationRunMetrics;
import com.flunav.backend.models.multisimulation.MultiSimulationRunStatus;
import com.flunav.backend.models.multisimulation.MultiSimulationStatus;
import com.flunav.backend.models.response.ConveyorResponse;
import com.flunav.backend.models.response.LocationResponse;
import com.flunav.backend.repositories.LiveItemRepository;
import com.flunav.backend.repositories.support.MultiSimulationRuntimeStore;
import com.flunav.backend.services.MultiSimulationMetricsService;
import com.flunav.backend.services.MultiSimulationService;
import com.flunav.backend.services.SimulationService;

import flunav.events.DomainEvent;
import flunav.events.ItemCreatedEvent;
import flunav.types.ConveyorType;
import flunav.types.LocationType;
import flunav.types.PositionType;

@SpringBootTest(properties = {
        "springwolf.enabled=false", "state-recovery.enabled=false",
        "graph-snapshot.enabled=false", "metric-snapshot.enabled=false",
        "stale-item-cleanup.enabled=false", "multi-simulation.max-concurrent-runs=3",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "spring.rabbitmq.listener.direct.auto-startup=false"
})
class MultiSimulationMergePressureIntegrationTests extends BaseIntegrationTest {
    private static final Instant START = Instant.parse("2035-01-01T00:00:00Z");
    private static final long DURATION_SECONDS = 180;
    private static final double FEEDER_LENGTH = 12.0;
    private static final double REQUIRED_GAP = 0.25;

    @Autowired MultiSimulationService experiments;
    @Autowired SimulationService simulations;
    @Autowired MultiSimulationMetricsService metrics;
    @Autowired LiveItemRepository items;
    @Autowired MultiSimulationRuntimeStore runtimes;
    @Autowired org.springframework.data.redis.core.StringRedisTemplate redis;
    @Autowired ObjectMapper mapper;

    /** Compares deterministic batches while holding outlet capacity and initial spacing constant. */
    @Test
    void fasterFeedersAndLargerBatchesIncreaseMergeWaitingWithoutOverlapping() throws Exception {
        List<Result> results = new ArrayList<>();
        for (int count : new int[] {4, 12, 24}) {
            for (double speed : new double[] {0.05, 0.5, 1.0}) {
                MultiSimulationBaseline baseline = baseline(speed, count);
                var configuration = new MultiSimulationConfiguration(
                        "Merge pressure " + speed + " m/s " + count + " items", DURATION_SECONDS, 3,
                        new ArrivalConfiguration(1, ArrivalDistribution.FIXED, 0), "belt-source",
                        List.of(new DestinationProbability("exit", 1)), List.of(), 1234L, START);
                var experiment = experiments.createFromBaseline(configuration, baseline);
                experiments.start(experiment.id());
                awaitCompletion(experiment.id());
                var runs = experiments.runs(experiment.id());
                assertEquals(3, runs.size());
                var reference = runs.getFirst().metrics();
                assertNotNull(reference);
                for (var run : runs) {
                    assertEquals(MultiSimulationRunStatus.COMPLETED, run.status());
                    assertEquals(reference, run.metrics(), "Fixed inputs must give identical metrics across seeds");
                    assertEquals(count, run.metrics().itemsGenerated());
                    assertEquals(count, run.metrics().itemsCompleted());
                    assertEquals(0, run.metrics().itemsRemaining());
                    String runId = experiment.id() + "_run_" + run.runIndex();
                    assertFalse(runtimes.contains(runId));
                    assertTrue(redis.keys("sim:" + runId + ":*").isEmpty());
                }
                Result result = observe(baseline, speed, count, reference);
                results.add(result);
                System.out.println("MERGE_PRESSURE " + mapper.writeValueAsString(result));
            }
        }
        Path output = Path.of("target/benchmark-results/merge-pressure.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(results));
        for (int count : new int[] {12, 24}) {
            assertTrue(result(results, 1.0, count).averageWaitingSeconds()
                    > result(results, 0.05, count).averageWaitingSeconds());
        }
        for (double speed : new double[] {0.05, 0.5, 1.0}) {
            assertTrue(result(results, speed, 24).totalWaitingItemSeconds()
                    > result(results, speed, 4).totalWaitingItemSeconds());
        }
    }

    /** Observes the same multi-run engine at event boundaries and integrates actual flow pauses. */
    private Result observe(MultiSimulationBaseline baseline, double speed, int count,
            MultiSimulationRunMetrics reference) {
        String id = "merge-pressure-observer-" + System.nanoTime();
        metrics.start(id);
        try {
            var state = simulations.createMultiSimulationRuntime(id, START, baseline);
            try (var context = DatabaseContextHolder.enterSimulationContext(id);
                    var cache = SimulationBuildCacheContext.enterMultiRun(id)) {
                Instant previous = START;
                double waiting = 0;
                int peakWaiting = 0;
                double minimumGap = Double.POSITIVE_INFINITY;
                HashSet<String> waited = new HashSet<>();
                long events = 0;
                while (!state.getInternalEventQueue().isEmpty()) {
                    var next = state.getInternalEventQueue().peek();
                    assertFalse(next.getTimestamp().isAfter(START.plusSeconds(DURATION_SECONDS)),
                            "The batch must drain within the horizon");
                    assertTrue(++events < 100_000, "Merge must make progress");
                    var paused = items.getAllActiveItems().stream().filter(RedisLiveItem::isFlowPaused).toList();
                    waiting += paused.size() * Duration.between(previous, next.getTimestamp()).toNanos() / 1e9;
                    previous = next.getTimestamp();
                    minimumGap = Math.min(minimumGap,
                            checkGaps(items.getAllActiveItems(), baseline.graph(), previous));
                    simulations.processNextInternalEvent(id);
                    var current = items.getAllActiveItems();
                    var waitingNow = current.stream().filter(RedisLiveItem::isFlowPaused).toList();
                    waitingNow.forEach(item -> waited.add(item.getId()));
                    peakWaiting = Math.max(peakWaiting, waitingNow.size());
                    minimumGap = Math.min(minimumGap, checkGaps(current, baseline.graph(), previous));
                }
                var observed = metrics.finish(id, DURATION_SECONDS, START.plusSeconds(DURATION_SECONDS));
                assertEquals(reference, observed, "Observation must match the ordinary multi-simulation runs");
                return new Result(speed, count, 3, reference.itemsCompleted(),
                        reference.averageJourneyTimeSeconds(), reference.p95JourneyTimeSeconds(),
                        waited.size(), peakWaiting, waiting, waiting / count, minimumGap);
            }
        } finally {
            metrics.discard(id);
            simulations.destroySimulation(id);
            assertFalse(runtimes.contains(id));
        }
    }

    /** Projects the stored physical state without clamping gaps, which would hide overlaps. */
    private double checkGaps(List<RedisLiveItem> current, GraphData graph, Instant at) {
        double minimum = Double.POSITIVE_INFINITY;
        for (var conveyor : graph.getConveyors()) {
            var positions = current.stream()
                    .filter(item -> item.getType() == PositionType.CONVEYOR
                            && conveyor.getId().equals(item.getPositionId()))
                    .map(item -> item.getAccumulatedDistance() / 100 * conveyor.getLength()
                            + (item.isFlowPaused() || item.isMovementPaused() ? 0
                            : Math.max(0, Duration.between(item.getEntryTime(), at).toNanos() / 1e9)
                                    * conveyor.getSpeed()))
                    .sorted().toList();
            for (int index = 1; index < positions.size(); index++) {
                double gap = positions.get(index) - positions.get(index - 1);
                minimum = Math.min(minimum, gap);
                // Admission checks have millisecond resolution; sample the approach before its stop event too.
                assertTrue(gap >= REQUIRED_GAP - conveyor.getSpeed() * 0.001 - 1e-6,
                        conveyor.getId() + " center gap " + gap + " at " + at);
            }
        }
        return minimum;
    }

    private MultiSimulationBaseline baseline(double speed, int count) {
        var locations = List.of(location("belt-source", LocationType.JUNCTION),
                location("roller-source", LocationType.JUNCTION), location("merge", LocationType.JUNCTION),
                location("exit", LocationType.CHUTE));
        var conveyors = List.of(conveyor("belt", "belt-source", "merge", FEEDER_LENGTH, speed, ConveyorType.BELT),
                conveyor("roller", "roller-source", "merge", FEEDER_LENGTH, speed, ConveyorType.ROLLER),
                conveyor("outlet", "merge", "exit", 2, 0.1, ConveyorType.BELT));
        List<DomainEvent> batch = new ArrayList<>();
        for (String feeder : List.of("belt", "roller")) {
            for (int index = 0; index < count / 2; index++) {
                String id = feeder + "-" + index;
                double meters = FEEDER_LENGTH - 0.3 - index * 0.4;
                batch.add(new ItemCreatedEvent(id, id, 1.0, 0.0, true, feeder, PositionType.CONVEYOR,
                        meters / FEEDER_LENGTH * 100, List.of("exit"), Map.of("lengthCm", 20), START));
            }
        }
        return new MultiSimulationBaseline(new GraphData(locations, conveyors, List.of(), START),
                batch, START, "merge-pressure-v1", "batch-v1");
    }

    private LocationResponse location(String id, LocationType type) {
        return new LocationResponse(id, id, type, true, 0.0, 0.0, 1000, null, Map.of(), null);
    }

    private ConveyorResponse conveyor(String id, String source, String target, double length,
            double speed, ConveyorType type) {
        return new ConveyorResponse(id, source, target, id, length, speed, 0.05,
                type, true, true, 1000, Map.of(), null);
    }

    private void awaitCompletion(String id) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofMinutes(2).toNanos();
        while (true) {
            var status = experiments.get(id).status();
            if (status == MultiSimulationStatus.COMPLETED) return;
            assertTrue(status == MultiSimulationStatus.RUNNING || status == MultiSimulationStatus.QUEUED,
                    "Experiment ended with " + status + ": " + experiments.runs(id));
            assertTrue(System.nanoTime() < deadline, "Experiment timed out");
            Thread.sleep(100);
        }
    }

    private Result result(List<Result> results, double speed, int count) {
        return results.stream().filter(value -> value.feederSpeedMetersPerSecond() == speed
                && value.items() == count).findFirst().orElseThrow();
    }

    private record Result(double feederSpeedMetersPerSecond, int items, int runs, long completed,
            double averageJourneySeconds, double p95JourneySeconds, int itemsThatWaited,
            int peakWaitingItems, double totalWaitingItemSeconds, double averageWaitingSeconds,
            double minimumObservedCenterGapMeters) {}
}
