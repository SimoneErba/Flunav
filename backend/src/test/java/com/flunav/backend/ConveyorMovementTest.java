package com.flunav.backend;

import com.flunav.backend.test.SimulationTestHarness;
import com.flunav.backend.services.OrientDBService;
import com.flunav.backend.services.ClickHouseService;
import flunav.events.ItemCreatedEvent;
import flunav.events.ConnectionSpeedChangedEvent;
import flunav.types.LocationType;
import flunav.types.PositionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.mockito.Mockito;
import com.orientechnologies.orient.core.db.ODatabaseSession;

import java.time.Instant;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "springwolf.enabled=false",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration",
        "rabbitmq.routing-key.item-events=test-key"
})
class ConveyorMovementTest {

    @Autowired
    private SimulationTestHarness sim;

    @MockBean
    private OrientDBService orientDBService;

    @MockBean
    private ClickHouseService clickHouseService;

    @MockBean
    private org.springframework.amqp.core.AmqpTemplate amqpTemplate;

    @MockBean
    private com.flunav.backend.services.PathfindingService pathfindingService;

    @MockBean
    private com.flunav.backend.services.ConveyorService conveyorService;

    @BeforeEach
    void setup() {
        Mockito.when(orientDBService.getSession()).thenReturn(Mockito.mock(ODatabaseSession.class));
        Mockito.when(pathfindingService.arePositionsConnected(Mockito.anyString(), Mockito.any(), Mockito.anyString(),
                Mockito.any())).thenReturn(true);
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
        sim.stubLocation(startLocId, "Start Node", LocationType.JUNCTION);
        sim.stubLocation(endLocId, "End Node", LocationType.JUNCTION);
        sim.stubConveyor(conveyorId, startLocId, endLocId, 10.0, 1.0);

        // 1. Create item at the start of the conveyor
        sim.applyEvent(new ItemCreatedEvent(itemId, "Box", 1.0, true, conveyorId, PositionType.CONVEYOR, 0.0,
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
        sim.stubLocation(startLocId, "Start Node", LocationType.JUNCTION);
        sim.stubLocation(endLocId, "End Node", LocationType.JUNCTION);
        sim.stubConveyor(conveyorId, startLocId, endLocId, 10.0, 1.0);

        // 1. Create item
        sim.applyEvent(new ItemCreatedEvent(itemId, "Box", 1.0, true, conveyorId, PositionType.CONVEYOR, 0.0,
                new HashMap<>(), startTime));

        // 2. Advance 2 seconds -> progress 0.2
        sim.advanceSeconds(2);
        var item = sim.getItem(itemId).orElseThrow();
        assertEquals(0.2, item.getProgress(), 0.05);

        // 3. Deactivate conveyor (set speed to 0)
        sim.applyEvent(new ConnectionSpeedChangedEvent(conveyorId, 0.0, startTime.plusSeconds(2)));
        sim.getConveyor(conveyorId).setSpeed(0.0); // Update stub too

        // 4. Advance 5 seconds -> should still be at 0.2
        sim.advanceSeconds(5);
        item = sim.getItem(itemId).orElseThrow();
        assertEquals(0.2, item.getProgress(), 0.05, "Item should have stopped at 20%");

        // 5. Reactivate conveyor (set speed to 1.0)
        sim.applyEvent(new ConnectionSpeedChangedEvent(conveyorId, 1.0, startTime.plusSeconds(7)));
        sim.getConveyor(conveyorId).setSpeed(1.0); // Update stub too

        // 6. Advance 3 seconds -> should be at 0.2 + (3s * 1m/s / 10m) = 0.5
        sim.advanceSeconds(3);
        item = sim.getItem(itemId).orElseThrow();
        assertEquals(0.5, item.getProgress(), 0.05, "Item should have resumed and reached 50%");
    }
}
