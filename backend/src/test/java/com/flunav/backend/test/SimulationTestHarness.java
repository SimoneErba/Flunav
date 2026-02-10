package com.flunav.backend.test;

import com.flunav.backend.services.EventProcessor;
import com.flunav.backend.services.GraphService;
import com.flunav.backend.services.TimeService;
import com.flunav.backend.services.SimulationService;
import com.flunav.backend.models.graph.GraphData;
import com.flunav.backend.models.response.ItemResponse;
import com.flunav.backend.domain.Location;
import com.flunav.backend.domain.Conveyor;
import flunav.events.DomainEvent;
import flunav.types.LocationType;
import flunav.types.ConveyorType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.Optional;

/**
 * Helper component for writing deterministic simulation tests.
 */
@Component
public class SimulationTestHarness {

    @Autowired
    private EventProcessor eventProcessor;

    @Autowired
    private GraphService graphService;

    @Autowired
    private TimeService timeService;

    @Autowired
    private MockTopologyProvider mockTopologyProvider;

    @Autowired
    private SimulationService simulationService;

    private Instant currentTurnTime;

    /**
     * Stubs a location for the simulation.
     */
    public void stubLocation(String id, String name, LocationType type) {
        try (var ctx = com.flunav.backend.context.DatabaseContextHolder.enterSimulationContext("test-sim")) {
            Location loc = new Location(id, name, type, true, new HashMap<>(), 0.0, 0.0, 100);
            mockTopologyProvider.stubLocation(loc);
        }
    }

    /**
     * Stubs a conveyor for the simulation.
     */
    public void stubConveyor(String id, String sourceId, String targetId, double length, double speed) {
        try (var ctx = com.flunav.backend.context.DatabaseContextHolder.enterSimulationContext("test-sim")) {
            Conveyor conv = new Conveyor(id, sourceId, targetId, length, speed, 1.0, ConveyorType.BELT, true, 100, false, new HashMap<>());
            mockTopologyProvider.stubConveyor(conv);
        }
    }

    public Conveyor getConveyor(String id) {
        return mockTopologyProvider.getConveyorById(id);
    }

    /**
     * Initializes the simulation at a specific timestamp.
     */
    public void startAt(Instant startTime) {
        this.currentTurnTime = startTime;
        System.setProperty("simulation.id", "test-sim");
        timeService.useFixedClock(startTime);
        simulationService.getOrCreateSimulation("test-sim", startTime);
    }

    /**
     * Applies an event at the current simulation time.
     */
    public void applyEvent(DomainEvent event) {
        try (var ctx = com.flunav.backend.context.DatabaseContextHolder.enterSimulationContext("test-sim")) {
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
        // Process internal events that happen between now and target
        try (var ctx = com.flunav.backend.context.DatabaseContextHolder.enterSimulationContext("test-sim")) {
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

    public void reset() {
        timeService.reset();
        mockTopologyProvider.clear();
        simulationService.destroySimulation("test-sim");
    }
}