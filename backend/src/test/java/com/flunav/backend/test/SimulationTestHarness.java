package com.flunav.backend.test;

import com.flunav.backend.services.ConveyorService;
import com.flunav.backend.services.EventProcessor;
import com.flunav.backend.services.GraphService;
import com.flunav.backend.services.OrientDBService;
import com.flunav.backend.services.TimeService;
import com.flunav.backend.services.SimulationService;
import com.flunav.backend.repositories.LiveSimulationRepository;
import com.flunav.backend.models.simulation.SimulationStatus;
import com.flunav.backend.models.graph.GraphData;
import com.flunav.backend.models.response.ItemResponse;
import com.flunav.backend.domain.Conveyor;
import com.flunav.backend.context.DatabaseContextHolder;

import flunav.events.ConnectionCreatedEvent;
import flunav.events.DomainEvent;
import flunav.events.LocationCreatedEvent;
import flunav.types.LocationType;
import flunav.types.ConveyorType;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.Optional;
import java.util.Objects;

/**
 * Helper component for writing deterministic simulation tests.
 */
@Component
public class SimulationTestHarness {

    private final EventProcessor eventProcessor;
    private final GraphService graphService;
    private final TimeService timeService;
    private final SimulationService simulationService;
    private final LiveSimulationRepository simulations;
    private final ConveyorService conveyorService;
    private final OrientDBService orientDBService;
    private final StringRedisTemplate redisTemplate;

    private Instant currentTurnTime;

    public SimulationTestHarness(
            EventProcessor eventProcessor,
            GraphService graphService,
            TimeService timeService,
            SimulationService simulationService,
            LiveSimulationRepository simulations,
            ConveyorService conveyorService,
            OrientDBService orientDBService,
            StringRedisTemplate redisTemplate) {
        this.eventProcessor = eventProcessor;
        this.graphService = graphService;
        this.timeService = timeService;
        this.simulationService = simulationService;
        this.simulations = simulations;
        this.conveyorService = conveyorService;
        this.orientDBService = orientDBService;
        this.redisTemplate = redisTemplate;
    }

    public void createLocation(String id, String name, LocationType type) {
        createLocation(id, name, type, null);
    }

    public void createLocation(String id, String name, LocationType type, Long timeToProcessMs) {
        try (var ctx = DatabaseContextHolder.enterSimulationContext("test-sim")) {
            var loc = new LocationCreatedEvent(id, name, true, 0.0, 0.0, type, 100, new HashMap<>(),
                    timeToProcessMs);
            applyEvent(loc);
        }
    }

    public void createConveyor(String id, String sourceId, String targetId, double length, double speed,
            Boolean isMainPath) {
        try (var ctx = DatabaseContextHolder.enterSimulationContext("test-sim")) {
            var conv = new ConnectionCreatedEvent(id, sourceId, targetId, length, speed, 0.0, null, isMainPath, id,
                    true,
                    ConveyorType.BELT, 100, new HashMap<>());
            applyEvent(conv);
        }
    }

    public Conveyor getConveyor(String id) {
        try (var ctx = DatabaseContextHolder.enterSimulationContext("test-sim")) {
            return conveyorService.getConveyorById(id);
        }
    }

    /**
     * Initializes the simulation at a specific timestamp.
     */
    public void startAt(Instant startTime) {
        this.currentTurnTime = startTime;

        orientDBService.createInMemoryDatabase("test-sim");

        timeService.useFixedClock(startTime);
        // Eager storage avoids a historical build racing with the topology created by the test.
        simulations.saveState(new LiveSimulationRepository.SimulationMetadata(
                "test-sim", startTime, SimulationStatus.READY, startTime, startTime, 1.0, 100.0));
        simulationService.getOrCreateSimulation("test-sim", startTime);
    }

    /**
     * Applies an event at the current simulation time.
     */
    public void applyEvent(DomainEvent event) {
        try (var ctx = DatabaseContextHolder.enterSimulationContext("test-sim")) {
            eventProcessor.processEvent(event, false);
        }
    }

    /**
     * Advances the simulation clock to a new target time.
     */
    public void advanceTo(Instant targetTime) {
        if (targetTime.isBefore(currentTurnTime)) {
            throw new IllegalArgumentException("Cannot move simulation backwards in time");
        }
        try (var ctx = DatabaseContextHolder.enterSimulationContext("test-sim")) {
            simulationService.processEventsUntil("test-sim", targetTime);
        }
        this.currentTurnTime = targetTime;
        timeService.useFixedClock(targetTime);
    }

    /**
     * Advances the simulation by a certain number of seconds.
     */
    public void advanceSeconds(long seconds) {
        advanceTo(currentTurnTime.plusSeconds(seconds));
    }

    public Instant getCurrentTime() {
        return currentTurnTime;
    }

    /**
     * Captures the current state of the world.
     */
    public GraphData getGameState() {
        return graphService.getGraphData(currentTurnTime, false, "test-sim", false);
    }

    /**
     * Helper to find an item by ID in the current state.
     */
    public Optional<ItemResponse> getItem(String itemId) {
        var items = getGameState().getItems();
        return items.stream()
                .filter(i -> i.getId().equals(itemId))
                .findFirst();
    }

    /**
     * Full Reset: Cleans OrientDB, Redis, TimeService, and ThreadLocals.
     * Call this in @AfterEach.
     */
    public void reset() {
        timeService.reset();

        DatabaseContextHolder.clearSimulation();

        try {
            simulationService.destroySimulation("test-sim");
        } catch (Exception ignored) {
        }

        try {
            orientDBService.dropDatabase("test-sim");
        } catch (Exception ignored) {
        }

        try {
            Objects.requireNonNull(redisTemplate.getConnectionFactory())
                    .getConnection()
                    .serverCommands()
                    .flushAll();
        } catch (Exception e) {
            System.err.println("Warning: Failed to flush Redis during test reset: " + e.getMessage());
        }

        System.clearProperty("simulation.id");

        DatabaseContextHolder.clearSimulation();
    }
}
