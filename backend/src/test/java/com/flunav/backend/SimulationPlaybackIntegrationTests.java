package com.flunav.backend;

import com.flunav.backend.context.DatabaseContextHolder;
import com.flunav.backend.models.graph.GraphData;
import com.flunav.backend.models.response.ItemResponse;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;

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
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration",
        "rabbitmq.routing-key.item-events=test-key",
        "stale-item-cleanup.enabled=false",
        "state-recovery.enabled=false",
        "graph-snapshot.enabled=false",
        "simulation.manage-logic=true"
})
class SimulationPlaybackIntegrationTests extends BaseIntegrationTest {

    @Autowired
    private SimulationTestHarness sim;

    @Autowired
    private EventProcessor eventProcessor;

    @Autowired
    private GraphService graphService;

    @Autowired
    private SimulationService simulationService;

    @Autowired
    private LiveSimulationRepository liveSimulationRepository;

    @Autowired
    private ClickHouseService clickHouseService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private TimeService timeService;

    @MockBean
    private org.springframework.amqp.core.AmqpTemplate amqpTemplate;

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
