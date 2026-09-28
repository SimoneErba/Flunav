package com.flunav.backend;

import com.flunav.backend.context.DatabaseContextHolder;
import com.flunav.backend.repositories.LiveItemRepository;
import com.flunav.backend.repositories.LiveSimulationRepository;
import com.flunav.backend.services.GraphService;
import com.flunav.backend.services.OrientDBService;
import com.flunav.backend.services.SimulationService;
import com.flunav.backend.test.SimulationTestHarness;

import flunav.events.ItemActivatedEvent;
import flunav.events.ItemCreatedEvent;
import flunav.events.ItemDeactivatedEvent;
import flunav.events.ItemPositionChangedEvent;
import flunav.types.LocationType;
import flunav.types.PositionType;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestConstructor;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Set;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "springwolf.enabled=false",
        "rabbitmq.routing-key.item-events=1",
        "stale-item-cleanup.enabled=false",
        "state-recovery.enabled=false",
        "graph-snapshot.enabled=false",
        "simulation.manage-logic=true"
})
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class SimulationTests extends BaseIntegrationTest {

    private final SimulationTestHarness sim;
    private final LiveItemRepository liveItemRepository;
    private final LiveSimulationRepository liveSimulationRepository;
    private final SimulationService simulationService;
    private final StringRedisTemplate redisTemplate;
    private final OrientDBService orientDBService;
    private final GraphService graphService;

    SimulationTests(
            SimulationTestHarness sim,
            LiveItemRepository liveItemRepository,
            LiveSimulationRepository liveSimulationRepository,
            SimulationService simulationService,
            StringRedisTemplate redisTemplate,
            OrientDBService orientDBService,
            GraphService graphService) {
        this.sim = sim;
        this.liveItemRepository = liveItemRepository;
        this.liveSimulationRepository = liveSimulationRepository;
        this.simulationService = simulationService;
        this.redisTemplate = redisTemplate;
        this.orientDBService = orientDBService;
        this.graphService = graphService;
    }

    @BeforeEach
    void setup() {
        System.setProperty("disable-sim-cleanup", "true");
        System.setProperty("simulation.id", "test-sim");
        liveItemRepository.deleteAllItems();
        sim.reset();
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        System.clearProperty("simulation.id");
    }

    @Test
    void testItemMovementOnConveyor() {
        DatabaseContextHolder.enterSimulationContext("test-sim");
        Instant start = Instant.parse("2026-02-07T10:00:00Z");
        sim.startAt(start);

        // Setup: StartNode -> (Conv1: 10000m, 1m/s) -> EndNode
        sim.createLocation("start", "Start", LocationType.GENERIC);
        sim.createLocation("end", "End", LocationType.GENERIC);
        sim.createConveyor("conv1", "start", "end", 10000.0, 1.0, false);

        // 1. Create item at start of conveyor
        sim.applyEvent(new ItemCreatedEvent("item-1", "Box", 1.0, true, "start", PositionType.LOCATION, 0.0,
                new HashMap<>(), start));

        // 2. Advance 5000 seconds
        sim.advanceSeconds(5000);

        // 3. Verify progress is 50%
        var item = sim.getItem("item-1").orElseThrow();
        assertEquals(0.5, item.getProgress(), 0.01, "Item should be halfway");
        assertEquals("conv1", item.getCurrentEdgeId());

        // 4. Advance another 4000 seconds (total 9000s)
        sim.advanceSeconds(4000);
        item = sim.getItem("item-1").orElseThrow();
        assertEquals(0.9, item.getProgress(), 0.01, "Item should be near the end");

        // 5. Advance past the end (total 11000s)
        sim.advanceSeconds(2000);
        item = sim.getItem("item-1").orElseThrow();
        assertEquals(1.0, item.getProgress(), 0.01, "Item should be at the end");
        assertEquals("end", item.getLocationId());
    }

    @Test
    void checkpointedConveyorItemEntryTimestampMatchesGraphProgress() {
        DatabaseContextHolder.enterSimulationContext("test-sim");
        Instant start = Instant.parse("2026-02-07T10:40:00Z");
        Instant checkpoint = start.plusSeconds(5);
        sim.startAt(start);

        sim.createLocation("checkpoint-start", "Start", LocationType.GENERIC);
        sim.createLocation("checkpoint-end", "End", LocationType.GENERIC);
        sim.createConveyor("checkpoint-conveyor", "checkpoint-start", "checkpoint-end", 100.0, 1.0, false);

        sim.applyEvent(new ItemCreatedEvent("checkpoint-item", "Box", 1.0, true, "checkpoint-start",
                PositionType.LOCATION, 0.0, new HashMap<>(), start));

        sim.advanceTo(checkpoint);

        var item = sim.getItem("checkpoint-item").orElseThrow();
        assertEquals("checkpoint-conveyor", item.getCurrentEdgeId());
        assertEquals(0.05, item.getProgress(), 0.001, "Item should have checkpointed conveyor progress");
        assertEquals(checkpoint, item.getEntryTimestamp(), "Entry timestamp should anchor the checkpoint progress");

        Instant nextCheckpoint = checkpoint.plusSeconds(5);
        double frontendDerivedProgress = item.getProgress()
                + (Duration.between(item.getEntryTimestamp(), nextCheckpoint).toMillis() / 1000.0) / 100.0;
        sim.advanceTo(nextCheckpoint);
        var advancedItem = sim.getItem("checkpoint-item").orElseThrow();
        assertEquals(0.10, advancedItem.getProgress(), 0.001);
        assertEquals(advancedItem.getProgress(), frontendDerivedProgress, 0.001,
                "Frontend-derived progress should match backend progress after the checkpoint");
    }

    @Test
    void testItemReachesChuteAndDisappears() {
        DatabaseContextHolder.enterSimulationContext("test-sim");
        Instant start = Instant.parse("2026-02-07T10:00:00Z");
        sim.startAt(start);

        sim.createLocation("start", "Start", LocationType.GENERIC);
        sim.createLocation("chute", "Exit", LocationType.CHUTE);
        sim.createConveyor("conv1", "start", "chute", 10000.0, 1.0, false);

        sim.applyEvent(new ItemCreatedEvent("item-1", "Box", 1.0, true, "start", PositionType.LOCATION, 0.0,
                new HashMap<>(), start));

        // Advance past the 10000s mark
        sim.advanceSeconds(11000);

        // Item should still be in the CHUTE (we changed logic to stay there until
        // emptied)
        assertTrue(sim.getItem("item-1").isPresent(), "Item should stay in CHUTE until emptied");
        assertEquals("chute", sim.getItem("item-1").get().getLocationId());

        // Now empty the chute
        sim.applyEvent(new flunav.events.ChuteEmptyEvent("chute", sim.getCurrentTime()));
        sim.advanceSeconds(5);

        // Item should now be gone
        assertTrue(sim.getItem("item-1").isEmpty(), "Item should be removed after ChuteEmptyEvent");
    }

    @Test
    void testConveyorTransfer() {
        DatabaseContextHolder.enterSimulationContext("test-sim");
        Instant start = Instant.parse("2026-02-07T10:00:00Z");
        sim.startAt(start);

        // Start -> Conv1 (10000m) -> Middle -> Conv2 (10000m) -> End
        sim.createLocation("start", "Start", LocationType.GENERIC);
        sim.createLocation("mid", "Middle", LocationType.GENERIC);
        sim.createLocation("end", "End", LocationType.GENERIC);
        sim.createConveyor("conv1", "start", "mid", 10000.0, 1.0, false);
        sim.createConveyor("conv2", "mid", "end", 10000.0, 1.0, false);

        sim.applyEvent(new ItemCreatedEvent("item-1", "Box", 1.0, true, "start", PositionType.LOCATION, 0.0,
                new HashMap<>(), start));

        // 15000 seconds: should be 5000 seconds into conv2
        sim.advanceSeconds(15000);

        var item = sim.getItem("item-1").orElseThrow();
        assertEquals("conv2", item.getCurrentEdgeId(), "Item should have transferred to conv2");
        assertEquals(0.5, item.getProgress(), 0.01, "Item should be halfway through conv2");
    }

    @Test
    void deactivatedItemFreezesAndActivationResumesFromItsCheckpoint() {
        DatabaseContextHolder.enterSimulationContext("test-sim");
        Instant start = Instant.parse("2026-02-07T11:00:00Z");
        sim.startAt(start);

        sim.createLocation("pause-start", "Start", LocationType.GENERIC);
        sim.createLocation("pause-end", "End", LocationType.GENERIC);
        sim.createConveyor("pause-conveyor", "pause-start", "pause-end", 100.0, 1.0, false);
        sim.applyEvent(new ItemCreatedEvent("pause-item", "Box", 1.0, true, "pause-start",
                PositionType.LOCATION, 0.0, new HashMap<>(), start));

        sim.applyEvent(new ItemDeactivatedEvent("pause-item", start.plusSeconds(20)));
        var frozenState = liveItemRepository.getItemState("pause-item");
        assertTrue(frozenState.isMovementPaused());
        assertEquals(20.0, frozenState.getAccumulatedDistance(), 0.001);

        sim.advanceTo(start.plusSeconds(60));
        var frozenItem = sim.getItem("pause-item").orElseThrow();
        assertEquals("pause-conveyor", frozenItem.getCurrentEdgeId());
        assertEquals(0.2, frozenItem.getProgress(), 0.001);

        sim.applyEvent(new ItemActivatedEvent("pause-item", start.plusSeconds(60)));
        assertFalse(liveItemRepository.getItemState("pause-item").isMovementPaused());

        sim.advanceTo(start.plusSeconds(139));
        assertEquals(0.99, sim.getItem("pause-item").orElseThrow().getProgress(), 0.01);
        sim.advanceTo(start.plusSeconds(141));
        assertEquals("pause-end", sim.getItem("pause-item").orElseThrow().getLocationId());
    }

    @Test
    void internalEventLeavesUnrelatedItemCheckpointLazyUntilObservation() {
        try (var ctx = DatabaseContextHolder.enterSimulationContext("test-sim")) {
            Instant start = Instant.parse("2026-02-07T12:00:00Z");
            sim.startAt(start);
            sim.createLocation("fast-start", "Fast Start", LocationType.GENERIC);
            sim.createLocation("fast-end", "Fast End", LocationType.GENERIC);
            sim.createLocation("slow-start", "Slow Start", LocationType.GENERIC);
            sim.createLocation("slow-end", "Slow End", LocationType.GENERIC);
            sim.createConveyor("fast-conveyor", "fast-start", "fast-end", 10.0, 1.0, false);
            sim.createConveyor("slow-conveyor", "slow-start", "slow-end", 100.0, 1.0, false);
            sim.applyEvent(new ItemCreatedEvent("fast-item", "Fast", 1.0, true, "fast-start",
                    PositionType.LOCATION, 0.0, new HashMap<>(), start));
            sim.applyEvent(new ItemCreatedEvent("slow-item", "Slow", 1.0, true, "slow-start",
                    PositionType.LOCATION, 0.0, new HashMap<>(), start));

            simulationService.getSimulationState("test-sim").getInternalEventQueue()
                    .removeIf(event -> event instanceof flunav.events.AnomalyEvaluationTickEvent);
            simulationService.addInternalEvent(new ItemPositionChangedEvent(
                    "fast-item", "fast-end", 1.0, start.plusSeconds(10)));
            var before = liveItemRepository.getItemState("slow-item");
            var processed = simulationService.processNextInternalEvent("test-sim");
            assertInstanceOf(ItemPositionChangedEvent.class, processed);
            assertEquals(start.plusSeconds(10), processed.getTimestamp());
            assertEquals(start.plusSeconds(10), simulationService.getSimulationState("test-sim")
                    .getLastProcessedTimestamp());

            var after = liveItemRepository.getItemState("slow-item");
            assertEquals(before.getEntryTime(), after.getEntryTime());
            assertEquals(before.getAccumulatedDistance(), after.getAccumulatedDistance(), 0.001);
            assertEquals(0.1, graphService.getGraphData(start.plusSeconds(10), false, "test-sim", false)
                    .getItems().stream()
                    .filter(item -> item.getId().equals("slow-item"))
                    .findFirst().orElseThrow().getProgress(), 0.001);

            simulationService.checkpointSimulationAt("test-sim", start.plusSeconds(10));
            var checkpointed = liveItemRepository.getItemState("slow-item");
            assertEquals(start.plusSeconds(10), checkpointed.getEntryTime());
            assertEquals(10.0, checkpointed.getAccumulatedDistance(), 0.001);
        }
    }

    @Test
    void cleanupAbandonedSimulationsRemovesStaleRedisBackedSimulations() {
        String simulationId = "stale-sim";
        orientDBService.createInMemoryDatabase(simulationId);
        redisTemplate.opsForValue().set("sim:" + simulationId + ":probe", "present");
        liveSimulationRepository.saveState(new LiveSimulationRepository.SimulationMetadata(
                simulationId,
                Instant.now().minus(Duration.ofMinutes(10)),
                com.flunav.backend.models.simulation.SimulationStatus.READY,
                Instant.now().minus(Duration.ofMinutes(3)),
                Instant.now().minus(Duration.ofMinutes(10)),
                1.0));

        System.clearProperty("disable-sim-cleanup");
        simulationService.cleanupAbandonedSimulations();

        assertFalse(liveSimulationRepository.exists(simulationId));
        Set<String> remainingKeys = redisTemplate.keys("sim:" + simulationId + ":*");
        assertTrue(remainingKeys == null || remainingKeys.isEmpty(), "Simulation Redis keys should be removed");
        assertThrows(IllegalStateException.class, () -> orientDBService.getSession(simulationId));
    }

    @Test
    void updateHeartbeatRefreshesRedisHeartbeatEvenWithoutLocalCacheState() {
        String simulationId = "remote-sim";
        Instant oldHeartbeat = Instant.now().minus(Duration.ofMinutes(5));
        liveSimulationRepository.saveState(new LiveSimulationRepository.SimulationMetadata(
                simulationId,
                Instant.now().minus(Duration.ofMinutes(20)),
                com.flunav.backend.models.simulation.SimulationStatus.READY,
                oldHeartbeat,
                Instant.now().minus(Duration.ofMinutes(10)),
                2.5));

        simulationService.updateHeartbeat(simulationId);

        Instant refreshedHeartbeat = liveSimulationRepository.getState(simulationId)
                .map(LiveSimulationRepository.SimulationMetadata::lastHeartbeatTimestamp)
                .orElseThrow();

        assertTrue(refreshedHeartbeat.isAfter(oldHeartbeat), "Heartbeat should be refreshed in Redis");
    }

    @Test
    void buildProgressIsPersistedMonotonicallyAndCompletedAtReady() {
        String simulationId = "progress-sim";
        Instant restoreTimestamp = Instant.parse("2026-06-07T09:00:00Z");
        liveSimulationRepository.saveState(new LiveSimulationRepository.SimulationMetadata(
                simulationId,
                restoreTimestamp,
                com.flunav.backend.models.simulation.SimulationStatus.BUILDING,
                Instant.now(),
                null,
                1.0,
                0.0));

        simulationService.updateBuildProgress(simulationId, 42.0, restoreTimestamp.minusSeconds(30));
        simulationService.updateBuildProgress(simulationId, 15.0, restoreTimestamp.minusSeconds(20));

        assertEquals(42.0, liveSimulationRepository.getState(simulationId)
                .map(LiveSimulationRepository.SimulationMetadata::buildProgress)
                .orElseThrow());

        simulationService.updateBuildProgress(simulationId, 120.0, restoreTimestamp);
        simulationService.updateSimulationStatus(
                simulationId,
                com.flunav.backend.models.simulation.SimulationStatus.READY,
                restoreTimestamp);

        var completedState = liveSimulationRepository.getState(simulationId).orElseThrow();
        assertEquals(100.0, completedState.buildProgress());
        assertEquals(com.flunav.backend.models.simulation.SimulationStatus.READY, completedState.status());

        simulationService.destroySimulation(simulationId);
    }

    @Test
    void createSimulationRejectsWhenActiveCapacityIsReached() {
        try {
            for (int index = 0; index < 3; index++) {
                String simulationId = "capacity-sim-" + index;
                liveSimulationRepository.saveState(new LiveSimulationRepository.SimulationMetadata(
                        simulationId,
                        Instant.now(),
                        com.flunav.backend.models.simulation.SimulationStatus.READY,
                        Instant.now(),
                        Instant.now(),
                        1.0));
            }

            ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                    () -> simulationService.createSimulation("capacity-overflow", Instant.now()));

            assertEquals(HttpStatus.TOO_MANY_REQUESTS, exception.getStatusCode());
        } finally {
            for (int index = 0; index < 3; index++) {
                simulationService.destroySimulation("capacity-sim-" + index);
            }
        }
    }

    @Test
    void simulationBuildStartsAndCompletesWithSingleBuildPermit() throws Exception {
        String simulationId = "single-build-sim";
        try {
            var created = simulationService.createSimulation(simulationId, Instant.now());
            assertTrue(Set.of(
                    com.flunav.backend.models.simulation.SimulationStatus.QUEUED,
                    com.flunav.backend.models.simulation.SimulationStatus.BUILDING)
                    .contains(created.getStatus()));

            waitFor(() -> simulationService.getSimulationState(simulationId).getStatus()
                    == com.flunav.backend.models.simulation.SimulationStatus.READY,
                    Duration.ofSeconds(20));
        } finally {
            simulationService.destroySimulation(simulationId);
        }
    }

    @Test
    void secondBuildQueuesUntilBuildingCapacityIsAvailable() throws Exception {
        String blockerId = "build-capacity-blocker";
        String queuedId = "queued-build-sim";
        Instant now = Instant.now();
        try {
            liveSimulationRepository.saveState(new LiveSimulationRepository.SimulationMetadata(
                    blockerId,
                    now,
                    com.flunav.backend.models.simulation.SimulationStatus.BUILDING,
                    now,
                    null,
                    1.0,
                    0.0));

            simulationService.createSimulation(queuedId, now);
            assertEquals(com.flunav.backend.models.simulation.SimulationStatus.QUEUED,
                    simulationService.getSimulationState(queuedId).getStatus());

            simulationService.updateSimulationStatus(
                    blockerId,
                    com.flunav.backend.models.simulation.SimulationStatus.READY,
                    now);
            simulationService.processWaitingQueue();

            waitFor(() -> simulationService.getSimulationState(queuedId).getStatus()
                    == com.flunav.backend.models.simulation.SimulationStatus.READY,
                    Duration.ofSeconds(20));
        } finally {
            simulationService.destroySimulation(queuedId);
            simulationService.destroySimulation(blockerId);
        }
    }

    private void waitFor(BooleanSupplier condition, Duration timeout) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(100);
        }
        fail("Condition was not met within " + timeout);
    }

    @AfterEach
    void autoReset() {
        if (sim != null) {
            sim.reset();
        }
    }
}
