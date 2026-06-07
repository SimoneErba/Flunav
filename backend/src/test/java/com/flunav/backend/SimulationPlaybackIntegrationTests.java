package com.flunav.backend;

import com.flunav.backend.context.DatabaseContextHolder;
import com.flunav.backend.models.graph.GraphData;
import com.flunav.backend.models.response.ItemResponse;
import com.flunav.backend.models.simulation.SimulationState;
import com.flunav.backend.models.simulation.SimulationStatus;
import com.flunav.backend.repositories.LiveSimulationRepository;
import com.flunav.backend.services.ClickHouseService;
import com.flunav.backend.services.EventProcessor;
import com.flunav.backend.services.GraphService;
import com.flunav.backend.services.SimulationService;
import com.flunav.backend.services.TimeService;
import com.flunav.backend.test.SimulationTestHarness;

import flunav.events.ConnectionCreatedEvent;
import flunav.events.ConnectionSpeedChangedEvent;
import flunav.events.ItemCreatedEvent;
import flunav.events.LocationCreatedEvent;
import flunav.types.ConveyorType;
import flunav.types.LocationType;
import flunav.types.PositionType;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestConstructor;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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
class SimulationPlaybackIntegrationTests extends BaseIntegrationTest {

    private final SimulationTestHarness sim;
    private final EventProcessor eventProcessor;
    private final GraphService graphService;
    private final SimulationService simulationService;
    private final LiveSimulationRepository liveSimulationRepository;
    private final ClickHouseService clickHouseService;
    private final StringRedisTemplate redisTemplate;
    private final TimeService timeService;

    SimulationPlaybackIntegrationTests(
            SimulationTestHarness sim,
            EventProcessor eventProcessor,
            GraphService graphService,
            SimulationService simulationService,
            LiveSimulationRepository liveSimulationRepository,
            ClickHouseService clickHouseService,
            StringRedisTemplate redisTemplate,
            TimeService timeService) {
        this.sim = sim;
        this.eventProcessor = eventProcessor;
        this.graphService = graphService;
        this.simulationService = simulationService;
        this.liveSimulationRepository = liveSimulationRepository;
        this.clickHouseService = clickHouseService;
        this.redisTemplate = redisTemplate;
        this.timeService = timeService;
    }

    @BeforeEach
    void setup() throws Exception {
        System.setProperty("disable-sim-cleanup", "true");
        DatabaseContextHolder.clearSimulation();
        sim.reset();
        flushRedis();
        truncateClickHouse();
        timeService.reset();
    }

    @AfterEach
    void tearDown() throws Exception {
        stopAndDestroy("sim-a");
        stopAndDestroy("sim-b");
        stopAndDestroy("sim-fast");
        stopAndDestroy("sim-slow");
        stopAndDestroy("sim-resume");
        stopAndDestroy("sim-future-anchor");
        stopAndDestroy("sim-future-playback");
        stopAndDestroy("sim-future-state");
        stopAndDestroy("sim-present-fastforward");
        stopAndDestroy("sim-future-clickhouse");
        stopAndDestroy("sim-clock");
        stopAndDestroy("sim-reschedule-speed");
        stopAndDestroy("sim-ready-speed");
        stopAndDestroy("sim-paused-speed");
        sim.reset();
        truncateClickHouse();
        flushRedis();
        timeService.reset();
        DatabaseContextHolder.clearSimulation();
        System.clearProperty("simulation.id");
    }

    @Test
    void conveyorSpeedIncreaseShortensRemainingTravelTime() {
        Instant startTime = Instant.parse("2026-02-07T13:00:00Z");
        sim.startAt(startTime);

        sim.stubLocation("start", "Start", LocationType.GENERIC);
        sim.stubLocation("end", "End", LocationType.GENERIC);
        sim.stubConveyor("conv-speed", "start", "end", 10.0, 1.0, false);

        sim.applyEvent(new ItemCreatedEvent("item-speed", "Box", 1.0, true, "start", PositionType.LOCATION, 0.0,
                new HashMap<>(), startTime));

        sim.advanceSeconds(4);
        ItemResponse item = sim.getItem("item-speed").orElseThrow();
        assertEquals(0.4, item.getProgress(), 0.05, "Item should be 40% through the conveyor before the speed change");

        Instant speedChangeTime = startTime.plusSeconds(4);
        sim.applyEvent(new ConnectionSpeedChangedEvent("conv-speed", 2.0, speedChangeTime));
        sim.getConveyor("conv-speed").setSpeed(2.0);

        sim.advanceSeconds(3);
        item = sim.getItem("item-speed").orElseThrow();
        assertEquals("end", item.getLocationId(), "Faster speed should shorten the remaining travel time");
        assertTrue(item.getProgress() >= 0.99, "Item should reach the end after the speed increase");
    }

    @Test
    void multipleSimulationsStayIsolatedAndDestroyingOneDoesNotAffectTheOther() throws Exception {
        Instant start = Instant.parse("2026-02-07T14:00:00Z");
        createEmptySimulation("sim-a", start);
        createEmptySimulation("sim-b", start);

        // Pass 10.0 and 80.0 to guarantee vastly different values
        createSimulationWorld("sim-a", 10.0, "Box A");
        createSimulationWorld("sim-b", 80.0, "Box B");

        ItemResponse simAItem = getSimulationItem("sim-a", start, "shared-item").orElseThrow();
        ItemResponse simBItem = getSimulationItem("sim-b", start, "shared-item").orElseThrow();

        // Assert they are isolated by confirming they have different values and names
        assertTrue(simAItem.getProgress() > 0.0, "Item A should have valid progress");
        assertTrue(simBItem.getProgress() > 0.0, "Item B should have valid progress");
        assertNotEquals(simAItem.getProgress(), simBItem.getProgress(),
                "Items should have different progress in isolated simulations");

        assertEquals("Box A", simAItem.getName());
        assertEquals("Box B", simBItem.getName());

        simulationService.destroySimulation("sim-a");

        assertFalse(liveSimulationRepository.exists("sim-a"));
        assertTrue(liveSimulationRepository.exists("sim-b"));
        assertThrows(RuntimeException.class, () -> graphService.getGraphData(start, false, "sim-a", false));

        ItemResponse survivingItem = getSimulationItem("sim-b", start, "shared-item").orElseThrow();
        assertEquals("Box B", survivingItem.getName());
        assertTrue(survivingItem.getProgress() > 0.0);

        Set<String> simBKeys = redisTemplate.keys("sim:sim-b:*");
        assertNotNull(simBKeys);
        assertFalse(simBKeys.isEmpty(), "Destroying sim-a must not remove sim-b Redis state");
    }

    @Test
    void playbackSpeedFactorAdvancesSimulationFaster() throws Exception {
        Instant itemStart = seedPlaybackScenario("speed");

        createPlaybackSimulation("sim-slow", itemStart);
        createPlaybackSimulation("sim-fast", itemStart);

        simulationService.startPlayback("sim-slow", 1.0);
        simulationService.startPlayback("sim-fast", 5.0);

        waitFor(() -> {
            Instant lastProcessed = simulationService.getSimulationState("sim-fast").getLastProcessedTimestamp();
            return lastProcessed != null && !lastProcessed.isBefore(itemStart.plusSeconds(10));
        }, Duration.ofSeconds(5), "Fast playback did not reach the expected simulation time");

        Instant slowProgress = simulationService.getSimulationState("sim-slow").getLastProcessedTimestamp();
        assertNotNull(slowProgress);
        assertTrue(slowProgress.isBefore(itemStart.plusSeconds(10)),
                "Slow playback should still lag behind the fast simulation");

        ItemResponse fastItem = getSimulationItem("sim-fast", itemStart.plusSeconds(10), "item-speed").orElseThrow();
        assertEquals("end-speed", fastItem.getLocationId(), "Fast playback should already have completed the conveyor");
    }

    @Test
    void playbackSpeedChangeReschedulesActivePlayback() throws Exception {
        Instant itemStart = seedPlaybackScenario("reschedule-speed");
        createPlaybackSimulation("sim-reschedule-speed", itemStart);

        simulationService.startPlayback("sim-reschedule-speed", 0.25);
        waitFor(() -> simulationService.getSimulationState("sim-reschedule-speed")
                .getStatus() == SimulationStatus.PLAYING,
                Duration.ofSeconds(3), "Playback did not start before speed change");

        Thread.sleep(300);
        simulationService.updatePlaybackSpeed("sim-reschedule-speed", 10.0);

        SimulationState afterSpeedChange = simulationService.getSimulationState("sim-reschedule-speed");
        assertEquals(SimulationStatus.PLAYING, afterSpeedChange.getStatus(),
                "Changing speed during playback must keep the simulation playing");
        assertEquals(10.0, afterSpeedChange.getSpeedFactor(), 0.001);

        Thread.sleep(500);
        assertEquals(SimulationStatus.PLAYING,
                simulationService.getSimulationState("sim-reschedule-speed").getStatus(),
                "Cancelling the old playback worker for reschedule must not mark the simulation stopped");

        waitFor(() -> {
            SimulationState state = simulationService.getSimulationState("sim-reschedule-speed");
            return state.getStatus() == SimulationStatus.PLAYING
                    && state.getLastProcessedTimestamp() != null
                    && !state.getLastProcessedTimestamp().isBefore(itemStart.plusSeconds(10));
        }, Duration.ofSeconds(5), "Playback did not advance at the rescheduled speed");

        ItemResponse item = getSimulationItem("sim-reschedule-speed", itemStart.plusSeconds(10),
                "item-reschedule-speed").orElseThrow();
        assertEquals("end-reschedule-speed", item.getLocationId(),
                "Rescheduled playback should use fresh item movement timing");
    }

    @Test
    void playbackSpeedChangeWhileNotPlayingOnlyStoresSpeed() throws Exception {
        Instant itemStart = seedPlaybackScenario("ready-speed");
        createPlaybackSimulation("sim-ready-speed", itemStart);

        simulationService.updatePlaybackSpeed("sim-ready-speed", 3.0);

        SimulationState readyState = simulationService.getSimulationState("sim-ready-speed");
        assertEquals(SimulationStatus.READY, readyState.getStatus());
        assertEquals(3.0, readyState.getSpeedFactor(), 0.001);
        assertEquals(itemStart.toEpochMilli(), readyState.getLastProcessedTimestamp().toEpochMilli(),
                "Updating speed while ready must not start playback");

        Instant pausedStart = seedPlaybackScenario("paused-speed");
        createPlaybackSimulation("sim-paused-speed", pausedStart);
        simulationService.startPlayback("sim-paused-speed", 4.0);
        waitFor(() -> simulationService.getSimulationState("sim-paused-speed")
                .getStatus() == SimulationStatus.PLAYING,
                Duration.ofSeconds(3), "Playback did not start before pause");
        simulationService.pauseSimulation("sim-paused-speed");
        Instant pausedAt = simulationService.getSimulationState("sim-paused-speed").getLastProcessedTimestamp();

        simulationService.updatePlaybackSpeed("sim-paused-speed", 6.0);
        Thread.sleep(800);

        SimulationState pausedState = simulationService.getSimulationState("sim-paused-speed");
        assertEquals(SimulationStatus.PAUSED, pausedState.getStatus());
        assertEquals(6.0, pausedState.getSpeedFactor(), 0.001);
        assertEquals(pausedAt, pausedState.getLastProcessedTimestamp(),
                "Updating speed while paused must not restart playback");
    }

    @Test
    void pauseAndResumePlaybackContinuesFromLastProcessedTimestamp() throws Exception {
        Instant itemStart = seedPlaybackScenario("resume");
        createPlaybackSimulation("sim-resume", itemStart);

        simulationService.startPlayback("sim-resume", 2.0);

        waitFor(() -> {
            Instant lastProcessed = simulationService.getSimulationState("sim-resume").getLastProcessedTimestamp();
            return lastProcessed != null && lastProcessed.isAfter(itemStart.plusSeconds(4));
        }, Duration.ofSeconds(5), "Playback did not advance before pause");

        simulationService.pauseSimulation("sim-resume");
        Instant pausedAt = simulationService.getSimulationState("sim-resume").getLastProcessedTimestamp();
        assertNotNull(pausedAt);
        assertEquals(SimulationStatus.PAUSED, simulationService.getSimulationState("sim-resume").getStatus());

        Thread.sleep(1200);

        Instant stillPausedAt = simulationService.getSimulationState("sim-resume").getLastProcessedTimestamp();
        assertEquals(pausedAt, stillPausedAt, "Paused playback should not continue to advance");

        simulationService.startPlayback("sim-resume", 4.0);

        waitFor(() -> {
            Instant lastProcessed = simulationService.getSimulationState("sim-resume").getLastProcessedTimestamp();
            return lastProcessed != null && lastProcessed.isAfter(pausedAt.plusSeconds(4));
        }, Duration.ofSeconds(5), "Playback did not continue after resume");

        Instant resumedAt = simulationService.getSimulationState("sim-resume").getLastProcessedTimestamp();
        assertTrue(resumedAt.isAfter(pausedAt), "Playback should resume from the paused timestamp");
    }

    @Test
    void simulationBuildUsesRestorePointAsPlaybackAnchorEvenInTheFuture() throws Exception {
        Instant restorePoint = Instant.now().plus(Duration.ofHours(2));

        simulationService.getOrCreateSimulation("sim-future-anchor", restorePoint);
        waitForStatus("sim-future-anchor", SimulationStatus.READY);

        SimulationState state = simulationService.getSimulationState("sim-future-anchor");
        assertEquals(restorePoint.toEpochMilli(), state.getLastProcessedTimestamp().toEpochMilli(),
                "Build should persist restore point as last processed timestamp");
    }

    @Test
    void futurePlaybackContinuesBeyondRealNowUsingInternalQueue() throws Exception {
        Instant virtualNow = Instant.parse("2026-02-07T18:00:00Z");
        timeService.useFixedClock(virtualNow);
        Instant restorePoint = virtualNow.plusSeconds(30);

        simulationService.getOrCreateSimulation("sim-future-playback", restorePoint);
        waitForStatus("sim-future-playback", SimulationStatus.READY);

        try (var ctx = DatabaseContextHolder.enterSimulationContext("sim-future-playback")) {
            eventProcessor.processEventWithoutBroadcast(new LocationCreatedEvent(
                    "future-start", "Future Start", true, 0.0, 0.0, LocationType.GENERIC, 100, new HashMap<>()));
            eventProcessor.processEventWithoutBroadcast(new LocationCreatedEvent(
                    "future-end", "Future End", true, 1.0, 1.0, LocationType.GENERIC, 100, new HashMap<>()));
            eventProcessor.processEventWithoutBroadcast(new ConnectionCreatedEvent(
                    "future-conv", "future-start", "future-end", 10.0, 1.0, 0.0, null, false,
                    "Future Conveyor", true, ConveyorType.BELT, 100, new HashMap<>()));
            eventProcessor.processEventWithoutBroadcast(new ItemCreatedEvent(
                    "future-item", "Future Box", 1.0, true, "future-start", PositionType.LOCATION, 0.0,
                    new HashMap<>(), restorePoint));
        }

        simulationService.startPlayback("sim-future-playback", 8.0);

        waitFor(() -> {
            Instant lastProcessed = simulationService.getSimulationState("sim-future-playback")
                    .getLastProcessedTimestamp();
            return lastProcessed != null && lastProcessed.isAfter(restorePoint.plusSeconds(12));
        }, Duration.ofSeconds(5), "Future playback did not advance past the future restore point");

        Instant lastProcessed = simulationService.getSimulationState("sim-future-playback").getLastProcessedTimestamp();
        assertTrue(lastProcessed.isAfter(virtualNow), "Simulation time should advance beyond real time");

        ItemResponse item = getSimulationItem("sim-future-playback", lastProcessed, "future-item").orElseThrow();
        assertEquals("future-end", item.getLocationId(),
                "Internal queued movement should continue while simulation is in the future");
    }

    @Test
    void futureSimulationBuildProjectsItemStateAtFutureTimestamp() throws Exception {
        clickHouseService.saveEventAsync(new LocationCreatedEvent(
                "future-state-start", "Start", true, 0.0, 0.0, LocationType.GENERIC, 100, new HashMap<>()));
        Thread.sleep(5);
        clickHouseService.saveEventAsync(new LocationCreatedEvent(
                "future-state-end", "End", true, 1.0, 1.0, LocationType.GENERIC, 100, new HashMap<>()));
        Thread.sleep(5);
        clickHouseService.saveEventAsync(new ConnectionCreatedEvent(
                "future-state-conv", "future-state-start", "future-state-end", 10.0, 1.0, 0.0, null, false,
                "Future State Conveyor", true, ConveyorType.BELT, 100, new HashMap<>()));
        Thread.sleep(5);

        Instant itemStart = Instant.now();
        clickHouseService.saveEventAsync(new ItemCreatedEvent(
                "future-state-item", "Future State Box", 1.0, true, "future-state-start", PositionType.LOCATION,
                0.0, new HashMap<>(), itemStart));
        clickHouseService.flushEvents();

        Instant restorePoint = itemStart.plus(Duration.ofHours(1));
        simulationService.getOrCreateSimulation("sim-future-state", restorePoint);
        waitForStatus("sim-future-state", SimulationStatus.READY);

        SimulationState state = simulationService.getSimulationState("sim-future-state");
        assertTrue(state.getInternalEventQueue().isEmpty(),
                "Build should process generated internal events up to the future restore point");
        assertFalse(state.getScheduledEventsByItem().containsKey("future-state-item"),
                "Build should not leave stale scheduled movement for an already projected item");

        ItemResponse item = getSimulationItem("sim-future-state", restorePoint, "future-state-item").orElseThrow();
        assertEquals("future-state-end", item.getLocationId(),
                "Future simulation should project item state at the requested future timestamp");
        assertTrue(item.getProgress() >= 0.99, "Item should have completed conveyor travel in projected future state");
    }

    @Test
    void presentSimulationFastForwardAtTwoXContinuesCorrectlyIntoFuture() throws Exception {
        Instant restorePoint = seedPlaybackScenario("present2x");
        simulationService.getOrCreateSimulation("sim-present-fastforward", restorePoint);
        waitForStatus("sim-present-fastforward", SimulationStatus.READY);

        simulationService.startPlayback("sim-present-fastforward", 2.0);

        waitFor(() -> {
            Instant lastProcessed = simulationService.getSimulationState("sim-present-fastforward")
                    .getLastProcessedTimestamp();
            return lastProcessed != null && lastProcessed.isAfter(restorePoint.plusSeconds(12));
        }, Duration.ofSeconds(8), "2x playback did not advance simulation into future");

        Instant lastProcessed = simulationService.getSimulationState("sim-present-fastforward")
                .getLastProcessedTimestamp();
        assertTrue(lastProcessed.isAfter(restorePoint), "Simulation clock should pass the present timestamp");

        ItemResponse item = getSimulationItem("sim-present-fastforward", lastProcessed, "item-present2x")
                .orElseThrow();
        assertEquals("end-present2x", item.getLocationId(),
                "Item should keep moving correctly after crossing from present into future");

        try (var ctx = DatabaseContextHolder.enterSimulationContext("sim-present-fastforward")) {
            GraphData currentGraph = graphService.getGraphData();
            assertEquals(lastProcessed.toEpochMilli(), currentGraph.getTimestamp().toEpochMilli(),
                    "Default simulation graph reads should use the simulation clock");
            ItemResponse currentItem = currentGraph.getItems().stream()
                    .filter(candidate -> "item-present2x".equals(candidate.getId()))
                    .findFirst()
                    .orElseThrow();
            assertEquals("end-present2x", currentItem.getLocationId(),
                    "Default graph reads should reflect playback progress, not the original restore point");
        }
    }

    @Test
    void playbackDoesNotReadClickHouseEventsBeyondPhysicalNowWhenWindowCrossesIt() throws Exception {
        Instant physicalNow = Instant.parse("2026-02-07T19:00:00Z");
        timeService.useFixedClock(physicalNow);
        Instant restorePoint = physicalNow.minusSeconds(1);

        createEmptySimulation("sim-future-clickhouse", restorePoint);
        createLinearTopology("sim-future-clickhouse", "future-clickhouse");

        clickHouseService.saveEventAsync(new ItemCreatedEvent(
                "future-clickhouse-item", "Future ClickHouse Box", 1.0, true, "future-clickhouse-start",
                PositionType.LOCATION, 0.0, new HashMap<>(), physicalNow.plusSeconds(1)));
        clickHouseService.flushEvents();

        simulationService.startPlayback("sim-future-clickhouse", 20.0);

        waitFor(() -> {
            Instant lastProcessed = simulationService.getSimulationState("sim-future-clickhouse")
                    .getLastProcessedTimestamp();
            return lastProcessed != null && lastProcessed.isAfter(physicalNow.plusSeconds(2));
        }, Duration.ofSeconds(5), "Playback did not advance across the physical-now boundary");

        Instant lastProcessed = simulationService.getSimulationState("sim-future-clickhouse")
                .getLastProcessedTimestamp();
        GraphData graphData = graphService.getGraphData(lastProcessed, false, "sim-future-clickhouse", false);
        assertTrue(graphData.getItems().stream()
                .noneMatch(item -> "future-clickhouse-item".equals(item.getId())),
                "Playback must ignore ClickHouse events after physical now once the simulation enters the future");
    }

    @Test
    void simulationVirtualClockDoesNotMutatePhysicalClock() throws Exception {
        Instant physicalNow = Instant.parse("2026-02-07T20:00:00Z");
        timeService.useFixedClock(physicalNow);
        Instant simulationStart = physicalNow.plus(Duration.ofHours(1));

        createEmptySimulation("sim-clock", simulationStart);
        createLinearTopology("sim-clock", "clock");

        try (var ctx = DatabaseContextHolder.enterSimulationContext("sim-clock")) {
            eventProcessor.processEventWithoutBroadcast(new ItemCreatedEvent(
                    "clock-item", "Clock Box", 1.0, true, "clock-start", PositionType.LOCATION, 0.0,
                    new HashMap<>(), simulationStart));
        }

        Instant targetTime = simulationStart.plusSeconds(15);
        simulationService.processEventsUntil("sim-clock", targetTime);

        assertEquals(physicalNow, timeService.physicalNow(),
                "Processing simulation events must not change the physical world clock");
        assertEquals(physicalNow, timeService.now(),
                "Outside a scoped simulation event, TimeService.now should report physical time");
        assertEquals(targetTime, simulationService.getSimulationClock("sim-clock"),
                "The simulation should keep its own independent clock");
    }

    private void createSimulationWorld(String simulationId, double initialProgress, String itemName) {
        try (var ctx = DatabaseContextHolder.enterSimulationContext(simulationId)) {
            eventProcessor.processEventWithoutBroadcast(new LocationCreatedEvent(
                    "shared-start", "Shared Start", true, 0.0, 0.0, LocationType.GENERIC, 100, new HashMap<>()));
            eventProcessor.processEventWithoutBroadcast(new LocationCreatedEvent(
                    "shared-end", "Shared End", true, 1.0, 1.0, LocationType.GENERIC, 100, new HashMap<>()));
            eventProcessor.processEventWithoutBroadcast(new ConnectionCreatedEvent(
                    "shared-conveyor", "shared-start", "shared-end", 10.0, 1.0, 0.0, null, false,
                    "Shared Conveyor", true, ConveyorType.BELT, 100, new HashMap<>()));
            eventProcessor.processEventWithoutBroadcast(new ItemCreatedEvent(
                    "shared-item", itemName, 1.0, true, "shared-conveyor", PositionType.CONVEYOR, initialProgress,
                    new HashMap<>(), Instant.parse("2026-02-07T14:00:00Z")));
        }
    }

    private void createLinearTopology(String simulationId, String prefix) {
        try (var ctx = DatabaseContextHolder.enterSimulationContext(simulationId)) {
            eventProcessor.processEventWithoutBroadcast(new LocationCreatedEvent(
                    prefix + "-start", "Start", true, 0.0, 0.0, LocationType.GENERIC, 100, new HashMap<>()));
            eventProcessor.processEventWithoutBroadcast(new LocationCreatedEvent(
                    prefix + "-end", "End", true, 1.0, 1.0, LocationType.GENERIC, 100, new HashMap<>()));
            eventProcessor.processEventWithoutBroadcast(new ConnectionCreatedEvent(
                    prefix + "-conv", prefix + "-start", prefix + "-end", 10.0, 1.0, 0.0, null, false,
                    "Conveyor", true, ConveyorType.BELT, 100, new HashMap<>()));
        }
    }

    private Instant seedPlaybackScenario(String suffix) throws Exception {
        clickHouseService.saveEventAsync(new LocationCreatedEvent(
                "start-" + suffix, "Start", true, 0.0, 0.0, LocationType.GENERIC, 100, new HashMap<>()));
        Thread.sleep(5); // Force ascending chronological order

        clickHouseService.saveEventAsync(new LocationCreatedEvent(
                "end-" + suffix, "End", true, 1.0, 1.0, LocationType.GENERIC, 100, new HashMap<>()));
        Thread.sleep(5);

        clickHouseService.saveEventAsync(new ConnectionCreatedEvent(
                "conv-" + suffix, "start-" + suffix, "end-" + suffix, 10.0, 1.0, 0.0, null, false,
                "Conveyor", true, ConveyorType.BELT, 100, new HashMap<>()));
        Thread.sleep(5);

        Instant itemStart = Instant.now();
        clickHouseService.saveEventAsync(new ItemCreatedEvent(
                "item-" + suffix, "Box", 1.0, true, "start-" + suffix, PositionType.LOCATION, 0.0,
                new HashMap<>(), itemStart));

        clickHouseService.flushEvents();
        return itemStart;
    }

    private void createEmptySimulation(String simulationId, Instant restorePoint) throws Exception {
        simulationService.getOrCreateSimulation(simulationId, restorePoint);
        waitForStatus(simulationId, SimulationStatus.READY);
        simulationService.updateLastProcessedTimestamp(simulationId, restorePoint);
    }

    private void createPlaybackSimulation(String simulationId, Instant restorePoint) throws Exception {
        simulationService.getOrCreateSimulation(simulationId, restorePoint);
        waitForStatus(simulationId, SimulationStatus.READY);
        simulationService.updateLastProcessedTimestamp(simulationId, restorePoint);
    }

    private Optional<ItemResponse> getSimulationItem(String simulationId, Instant currentTime, String itemId) {
        GraphData graphData = graphService.getGraphData(currentTime, false, simulationId, false);
        return graphData.getItems().stream()
                .filter(item -> itemId.equals(item.getId()))
                .findFirst();
    }

    private void waitForStatus(String simulationId, SimulationStatus expectedStatus) throws Exception {
        waitFor(() -> simulationService.getSimulationState(simulationId).getStatus() == expectedStatus,
                Duration.ofSeconds(10),
                "Simulation " + simulationId + " did not reach status " + expectedStatus);
    }

    private void waitFor(BooleanSupplier condition, Duration timeout, String errorMessage) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(100);
        }
        fail(errorMessage);
    }

    private void stopAndDestroy(String simulationId) {
        try {
            simulationService.cancelPlayback(simulationId);
        } catch (Exception ignored) {
        }

        try {
            simulationService.destroySimulation(simulationId);
        } catch (Exception ignored) {
        }
    }

    private void truncateClickHouse() throws Exception {
        clickHouseService.flushEvents();
        try (Connection connection = DriverManager.getConnection(
                CLICKHOUSE_CONTAINER.getJdbcUrl(),
                CLICKHOUSE_CONTAINER.getUsername(),
                CLICKHOUSE_CONTAINER.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("TRUNCATE TABLE Events");
            statement.execute("TRUNCATE TABLE snapshots");
        }
    }

    private void flushRedis() {
        Objects.requireNonNull(redisTemplate.getConnectionFactory())
                .getConnection()
                .serverCommands()
                .flushAll();
    }
}
