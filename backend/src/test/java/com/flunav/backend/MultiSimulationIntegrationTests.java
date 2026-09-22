package com.flunav.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
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
        "metric-snapshot.enabled=false"
})
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class MultiSimulationIntegrationTests extends BaseIntegrationTest {
    private final EventProcessor eventProcessor;
    private final MultiSimulationService multiSimulationService;
    private final StringRedisTemplate redis;

    MultiSimulationIntegrationTests(
            EventProcessor eventProcessor,
            MultiSimulationService multiSimulationService,
            StringRedisTemplate redis) {
        this.eventProcessor = eventProcessor;
        this.multiSimulationService = multiSimulationService;
        this.redis = redis;
    }

    @Test
    void runsTheSharedMovementEngineAndCleansEveryTemporaryNamespace() throws Exception {
        String suffix = String.valueOf(System.nanoTime());
        String source = "multi-source-" + suffix;
        String chute = "multi-chute-" + suffix;
        String conveyor = "multi-conveyor-" + suffix;
        String destination = "multi-destination-" + suffix;

        eventProcessor.process(new LocationCreatedEvent(
                source, "Source", true, 0.0, 0.0, LocationType.GENERIC, 100, new HashMap<>()), false).join();
        eventProcessor.process(new LocationCreatedEvent(
                chute, "Chute", true, 1.0, 0.0, LocationType.CHUTE, 100, new HashMap<>()), false).join();
        eventProcessor.process(new ConnectionCreatedEvent(
                conveyor, source, chute, 1.0, 1.0, 0.0, null, true,
                "Conveyor", true, ConveyorType.BELT, 100, new HashMap<>()), false).join();
        eventProcessor.process(new MapDestinationExitsEvent(List.of(
                new DestinationExitMappingRecord(destination, List.of(chute)))), false).join();

        MultiSimulationConfiguration configuration = new MultiSimulationConfiguration(
                "Integration multi-simulation",
                60,
                2,
                new ArrivalConfiguration(360, ArrivalDistribution.FIXED, 0),
                source,
                List.of(new DestinationProbability(destination, 1.0)),
                List.of(),
                1234L,
                Instant.parse("2035-01-01T00:00:00Z"));

        var created = multiSimulationService.create(configuration);
        multiSimulationService.start(created.id());
        waitForCompletion(created.id(), Duration.ofSeconds(30));

        var completed = multiSimulationService.get(created.id());
        assertEquals(MultiSimulationStatus.COMPLETED, completed.status());
        assertEquals(2, completed.completedRuns());
        assertEquals(0, completed.failedRuns());
        var runs = multiSimulationService.runs(created.id());
        assertEquals(2, runs.size());
        assertTrue(runs.stream().allMatch(run -> run.metrics() != null && run.metrics().itemsGenerated() == 5));
        assertTrue(runs.stream().allMatch(run -> run.metrics().itemsCompleted() == 5));
        assertTrue(runs.stream().allMatch(run -> run.metrics().itemsRemaining() == 0));
        assertNotNull(multiSimulationService.report(created.id()));

        for (int runIndex = 0; runIndex < 2; runIndex++) {
            String runtimeId = created.id() + "_run_" + runIndex;
            assertFalse(Boolean.TRUE.equals(redis.hasKey("sim:" + runtimeId + ":state")));
            assertTrue(redis.keys("sim:" + runtimeId + ":*").isEmpty());
        }
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
        throw new AssertionError("Multi-simulation did not finish before timeout");
    }
}
