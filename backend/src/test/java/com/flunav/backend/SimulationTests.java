package com.flunav.backend;

import com.flunav.backend.services.OrientDBService;
import com.flunav.backend.services.ClickHouseService;
import com.flunav.backend.test.SimulationTestHarness;
import com.flunav.backend.repositories.LiveItemRepository;
import com.flunav.backend.context.DatabaseContextHolder;
import flunav.events.ItemCreatedEvent;
import flunav.types.LocationType;
import flunav.types.PositionType;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import com.orientechnologies.orient.core.db.ODatabaseSession;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;

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
class SimulationTests extends BaseIntegrationTest {

    @Autowired
    private SimulationTestHarness sim;

    @Autowired
    private LiveItemRepository liveItemRepository;

    @MockBean
    private org.springframework.amqp.core.AmqpTemplate amqpTemplate;

    @Autowired
    private com.flunav.backend.services.PathfindingService pathfindingService;

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
        sim.stubLocation("start", "Start", LocationType.GENERIC);
        sim.stubLocation("end", "End", LocationType.GENERIC);
        sim.stubConveyor("conv1", "start", "end", 10000.0, 1.0);

        // 1. Create item at start of conveyor
        sim.applyEvent(new ItemCreatedEvent("item-1", "Box", 1.0, true, "conv1", PositionType.CONVEYOR, 0.0,
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
    void testItemReachesChuteAndDisappears() {
        DatabaseContextHolder.enterSimulationContext("test-sim");
        Instant start = Instant.parse("2026-02-07T10:00:00Z");
        sim.startAt(start);

        sim.stubLocation("start", "Start", LocationType.GENERIC);
        sim.stubLocation("chute", "Exit", LocationType.CHUTE);
        sim.stubConveyor("conv1", "start", "chute", 10000.0, 1.0);

        sim.applyEvent(new ItemCreatedEvent("item-1", "Box", 1.0, true, "conv1", PositionType.CONVEYOR, 0.0,
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
        sim.stubLocation("start", "Start", LocationType.GENERIC);
        sim.stubLocation("mid", "Middle", LocationType.GENERIC);
        sim.stubLocation("end", "End", LocationType.GENERIC);
        sim.stubConveyor("conv1", "start", "mid", 10000.0, 1.0);
        sim.stubConveyor("conv2", "mid", "end", 10000.0, 1.0);

        sim.applyEvent(new ItemCreatedEvent("item-1", "Box", 1.0, true, "conv1", PositionType.CONVEYOR, 0.0,
                new HashMap<>(), start));

        // 15000 seconds: should be 5000 seconds into conv2
        sim.advanceSeconds(15000);

        var item = sim.getItem("item-1").orElseThrow();
        assertEquals("conv2", item.getCurrentEdgeId(), "Item should have transferred to conv2");
        assertEquals(0.5, item.getProgress(), 0.01, "Item should be halfway through conv2");
    }

    @AfterEach
    void autoReset() {
        if (sim != null) {
            sim.reset();
        }
    }
}