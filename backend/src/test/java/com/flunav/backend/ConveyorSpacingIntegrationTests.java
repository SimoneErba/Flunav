package com.flunav.backend;

import com.flunav.backend.context.DatabaseContextHolder;
import com.flunav.backend.repositories.LiveConveyorRepository;
import com.flunav.backend.repositories.LiveItemRepository;
import com.flunav.backend.services.ItemMovementProcessor;
import com.flunav.backend.test.SimulationTestHarness;
import flunav.events.ConnectionCreatedEvent;
import flunav.events.ItemCreatedEvent;
import flunav.events.ItemMovementCheckEvent;
import flunav.types.ConveyorType;
import flunav.types.LocationType;
import flunav.types.PositionType;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestConstructor;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {"springwolf.enabled=false", "rabbitmq.routing-key.item-events=1"})
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class ConveyorSpacingIntegrationTests extends BaseIntegrationTest {
    private static final Instant START = Instant.parse("2026-02-07T10:00:00Z");
    private final SimulationTestHarness sim;
    private final LiveItemRepository items;
    private final LiveConveyorRepository conveyors;
    private final ItemMovementProcessor movement;

    ConveyorSpacingIntegrationTests(SimulationTestHarness sim, LiveItemRepository items,
            LiveConveyorRepository conveyors, ItemMovementProcessor movement) {
        this.sim = sim;
        this.items = items;
        this.conveyors = conveyors;
        this.movement = movement;
    }

    @BeforeEach
    void setup() {
        sim.reset();
        sim.startAt(START);
        sim.createLocation("a", "Belt", LocationType.JUNCTION);
        sim.createLocation("b", "Roller", LocationType.JUNCTION);
        sim.createLocation("merge", "Merge", LocationType.JUNCTION);
        sim.createLocation("exit", "Exit", LocationType.CHUTE);
        conveyor("belt", "a", "merge", 1.0, 1.0, ConveyorType.BELT);
        conveyor("roller", "b", "merge", 1.0, 1.0, ConveyorType.ROLLER);
        conveyor("shared", "merge", "exit", 2.0, 0.1, ConveyorType.BELT);
    }

    private void conveyor(String id, String source, String target, double length, double speed,
            ConveyorType type) {
        sim.applyEvent(new ConnectionCreatedEvent(id, source, target, length, speed, 0.05,
                null, true, id, true, type, 100, Map.of()));
    }

    private void item(String id, String conveyor, double progress, Map<String, Object> properties) {
        sim.applyEvent(new ItemCreatedEvent(id, id, 1.0, 0.0, true, conveyor,
                PositionType.CONVEYOR, progress, List.of("exit"), properties, START));
    }

    @Test
    void exactFitTransfersAndInsufficientGapRetriesWithLengthPrecedence() {
        item("occupant", "shared", 15.0, Map.of("lengthCm", 20, "length", 90));
        item("arrival", "belt", 100.0, Map.of("lengthCm", 30, "length", 5));
        // Required center gap is (20 + 30) / 200 + 0.05 = 0.30 m.
        sim.advanceTo(START.plusMillis(1));
        assertEquals("shared", sim.getItem("arrival").orElseThrow().getCurrentEdgeId());

        item("second", "roller", 100.0, Map.of());
        sim.advanceTo(START.plusMillis(2));
        try (var ignored = DatabaseContextHolder.enterSimulationContext("test-sim")) {
            assertEquals("roller", items.getItemState("second").getPositionId());
            assertTrue(items.getItemState("second").isFlowPaused());
            assertInstanceOf(ItemMovementCheckEvent.class,
                    movement.getScheduledEvent("second"));
        }
        sim.advanceTo(START.plusSeconds(4));
        assertEquals("shared", sim.getItem("second").orElseThrow().getCurrentEdgeId());
    }

    @Test
    void closerRollerArrivalWinsDespiteItsIdAndBeltCreationOrder() {
        item("occupant", "shared", 0.0, Map.of());
        item("a-belt", "belt", 60.0, Map.of());
        item("z-roller", "roller", 80.0, Map.of());
        sim.advanceTo(START.plusMillis(2200));
        try (var ignored = DatabaseContextHolder.enterSimulationContext("test-sim")) {
            assertEquals("shared", items.getItemState("z-roller").getPositionId());
            assertEquals("belt", items.getItemState("a-belt").getPositionId());
        }
    }

    @Test
    void blockedBeltFreezesEveryOccupantWhileRollerKeepsItsOwnFlow() {
        item("occupant", "shared", 0.0, Map.of());
        item("lead", "belt", 100.0, Map.of());
        item("follower", "belt", 40.0, Map.of());
        item("roller-arrival", "roller", 100.0, Map.of());
        sim.advanceTo(START.plusMillis(1));
        try (var ignored = DatabaseContextHolder.enterSimulationContext("test-sim")) {
            assertTrue(conveyors.isFlowStopped("belt"));
            assertFalse(conveyors.isFlowStopped("roller"));
            assertTrue(items.getItemState("lead").isFlowPaused());
            assertTrue(items.getItemState("follower").isFlowPaused());
            assertNull(items.getItemState("follower").getMovementCheckTimestamp());
            assertEquals(40.0, items.getItemState("follower").getAccumulatedDistance(), 0.01);
        }
        sim.advanceTo(START.plusSeconds(1));
        try (var ignored = DatabaseContextHolder.enterSimulationContext("test-sim")) {
            assertTrue(items.getItemState("follower").isFlowPaused());
            assertEquals(40.0, items.getItemState("follower").getAccumulatedDistance(), 0.01);
        }
        sim.advanceTo(START.plusMillis(2200));
        assertEquals("shared", sim.getItem("lead").orElseThrow().getCurrentEdgeId());
        try (var ignored = DatabaseContextHolder.enterSimulationContext("test-sim")) {
            assertFalse(conveyors.isFlowStopped("belt"));
            assertFalse(items.getItemState("follower").isFlowPaused());
        }
    }

    @AfterEach
    void cleanup() {
        sim.reset();
    }
}
