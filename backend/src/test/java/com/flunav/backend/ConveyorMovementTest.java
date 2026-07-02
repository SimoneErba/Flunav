package com.flunav.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flunav.backend.context.DatabaseContextHolder;
import com.flunav.backend.domain.Item;
import com.flunav.backend.services.RoutingDecisionService;
import com.flunav.backend.test.SimulationTestHarness;
import flunav.events.ItemCreatedEvent;
import flunav.events.ConnectionSpeedChangedEvent;
import flunav.events.ItemProcessingCompletedEvent;
import flunav.types.LocationType;
import flunav.types.PositionType;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestConstructor;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "springwolf.enabled=false",
        "rabbitmq.routing-key.item-events=1"
})
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class ConveyorMovementTest extends BaseIntegrationTest {

    private final SimulationTestHarness sim;
    private final RoutingDecisionService routingDecisionService;
    private final ObjectMapper objectMapper;

    ConveyorMovementTest(SimulationTestHarness sim, RoutingDecisionService routingDecisionService,
            ObjectMapper objectMapper) {
        this.sim = sim;
        this.routingDecisionService = routingDecisionService;
        this.objectMapper = objectMapper;
    }

    @BeforeEach
    void setup() {
        sim.reset();
    }

    @Test
    void testItemMovementOverTime() {
        Instant startTime = Instant.parse("2026-02-07T10:00:00Z");
        sim.startAt(startTime);

        String startLocId = "start-1";
        String endLocId = "end-1";
        String conveyorId = "conv-1";
        String itemId = "item-1";

        // Setup Topology: Start -> (Conveyor 10m, 1m/s) -> End
        sim.createLocation(startLocId, "Start Node", LocationType.JUNCTION);
        sim.createLocation(endLocId, "End Node", LocationType.JUNCTION);
        sim.createConveyor(conveyorId, startLocId, endLocId, 10.0, 1.0, false);

        // 1. Create item at the start of the conveyor
        sim.applyEvent(new ItemCreatedEvent(itemId, "Box", 1.0, true, startLocId, PositionType.LOCATION, 0.0,
                new HashMap<>(), startTime));

        // 2. Advance 5 seconds - should be at 50% (5m / 10m)
        sim.advanceSeconds(5);
        var item = sim.getItem(itemId).orElseThrow();
        assertEquals(0.5, item.getProgress(), 0.05, "Item should be halfway (5s at 1m/s on 10m conv)");
        assertEquals(conveyorId, item.getCurrentEdgeId());

        // 3. Advance another 5 seconds - should be at 100% or reached end
        sim.advanceSeconds(5);
        item = sim.getItem(itemId).orElseThrow();
        assertTrue(item.getProgress() >= 0.99, "Item should be at or near the end");
    }

    @Test
    void testItemStopsWhenConveyorDeactivated() {
        Instant startTime = Instant.parse("2026-02-07T12:00:00Z");
        sim.startAt(startTime);

        String startLocId = "start-2";
        String endLocId = "end-2";
        String conveyorId = "conv-2";
        String itemId = "item-2";

        // Setup Topology: Start -> (Conveyor 10m, 1m/s) -> End (Junction, not Chute so
        // it stays)
        sim.createLocation(startLocId, "Start Node", LocationType.JUNCTION);
        sim.createLocation(endLocId, "End Node", LocationType.JUNCTION);
        sim.createConveyor(conveyorId, startLocId, endLocId, 10.0, 1.0, false);

        // 1. Create item
        sim.applyEvent(new ItemCreatedEvent(itemId, "Box", 1.0, true, startLocId, PositionType.LOCATION, 0.0,
                new HashMap<>(), startTime));

        // 2. Advance 2 seconds -> progress 0.2
        sim.advanceSeconds(2);
        var item = sim.getItem(itemId).orElseThrow();
        assertEquals(0.2, item.getProgress(), 0.05);

        // 3. Deactivate conveyor (set speed to 0)
        sim.applyEvent(new ConnectionSpeedChangedEvent(conveyorId, 0.0, startTime.plusSeconds(2)));
        sim.getConveyor(conveyorId).setSpeed(0.0); // Update simulation graph too

        // 4. Advance 5 seconds -> should still be at 0.2
        sim.advanceSeconds(5);
        item = sim.getItem(itemId).orElseThrow();
        assertEquals(0.2, item.getProgress(), 0.05, "Item should have stopped at 20%");

        // 5. Reactivate conveyor (set speed to 1.0)
        sim.applyEvent(new ConnectionSpeedChangedEvent(conveyorId, 1.0, startTime.plusSeconds(7)));
        sim.getConveyor(conveyorId).setSpeed(1.0); // Update simulation graph too

        // 6. Advance 3 seconds -> should be at 0.2 + (3s * 1m/s / 10m) = 0.5
        sim.advanceSeconds(3);
        item = sim.getItem(itemId).orElseThrow();
        assertEquals(0.5, item.getProgress(), 0.05, "Item should have resumed and reached 50%");
    }

    @Test
    void timedNodeHoldsItemBeforeReleasingToNextConveyor() {
        Instant startTime = Instant.parse("2026-02-07T13:00:00Z");
        sim.startAt(startTime);

        String startLocId = "timed-start";
        String timedLocId = "timed-node";
        String endLocId = "timed-end";
        String firstConveyorId = "timed-conv-1";
        String secondConveyorId = "timed-conv-2";
        String itemId = "timed-item";

        sim.createLocation(startLocId, "Start Node", LocationType.JUNCTION);
        sim.createLocation(timedLocId, "Timed Node", LocationType.TIMED_NODE, 5_000L);
        sim.createLocation(endLocId, "End Node", LocationType.JUNCTION);
        sim.createConveyor(firstConveyorId, startLocId, timedLocId, 10.0, 1.0, true);
        sim.createConveyor(secondConveyorId, timedLocId, endLocId, 10.0, 1.0, true);

        sim.applyEvent(new ItemCreatedEvent(itemId, "Box", 1.0, true, startLocId, PositionType.LOCATION, 0.0,
                new HashMap<>(), startTime));

        sim.advanceSeconds(10);
        var item = sim.getItem(itemId).orElseThrow();
        assertEquals(timedLocId, item.getLocationId());
        assertNull(item.getCurrentEdgeId(), "Item should wait at the timed node before processing completes");

        sim.advanceSeconds(4);
        item = sim.getItem(itemId).orElseThrow();
        assertEquals(timedLocId, item.getLocationId());
        assertNull(item.getCurrentEdgeId(), "Item should not enter the next conveyor before the delay expires");

        sim.advanceSeconds(1);
        item = sim.getItem(itemId).orElseThrow();
        assertEquals(secondConveyorId, item.getCurrentEdgeId());
        assertEquals(0.0, item.getProgress(), 0.01, "Item should enter the next conveyor when processing completes");
    }

    @Test
    void routingShortestPathIncludesTimedNodeProcessingDelay() {
        Instant startTime = Instant.parse("2026-02-07T14:00:00Z");
        sim.startAt(startTime);

        sim.createLocation("route-start", "Start", LocationType.JUNCTION);
        sim.createLocation("route-timed", "Timed", LocationType.TIMED_NODE, 10_000L);
        sim.createLocation("route-plain", "Plain", LocationType.JUNCTION);
        sim.createLocation("route-exit", "Exit", LocationType.CHUTE);
        sim.createConveyor("route-start-timed", "route-start", "route-timed", 1.0, 1.0, true);
        sim.createConveyor("route-timed-exit", "route-timed", "route-exit", 1.0, 1.0, true);
        sim.createConveyor("route-start-plain", "route-start", "route-plain", 5.0, 1.0, false);
        sim.createConveyor("route-plain-exit", "route-plain", "route-exit", 5.0, 1.0, true);

        Item item = new Item("route-item", "Route Item", true, 1.0, Map.of());
        item.setDestinations(List.of("route-exit"));

        try (var ignored = DatabaseContextHolder.enterSimulationContext("test-sim")) {
            var decision = routingDecisionService.selectRoute(item, "route-start", PositionType.LOCATION);

            assertEquals(List.of("route-start", "route-plain", "route-exit"), decision.path());
            assertEquals("route-start-plain", decision.nextConveyorId());
        }
    }

    @Test
    void itemProcessingCompletedEventRoundTripsWithStableType() throws Exception {
        ItemProcessingCompletedEvent event = new ItemProcessingCompletedEvent("timed-item", "timed-node",
                Instant.parse("2026-02-07T15:00:00Z"));

        var restored = objectMapper.readValue(objectMapper.writeValueAsBytes(event), flunav.events.DomainEvent.class);

        ItemProcessingCompletedEvent restoredEvent = assertInstanceOf(ItemProcessingCompletedEvent.class, restored);
        assertEquals("ITEM_PROCESSING_COMPLETED", restoredEvent.getEventType());
        assertEquals("timed-item", restoredEvent.getEntityId());
        assertEquals("timed-node", restoredEvent.getLocationId());
    }

    @AfterEach
    void autoReset() {
        if (sim != null) {
            sim.reset();
        }
    }
}
