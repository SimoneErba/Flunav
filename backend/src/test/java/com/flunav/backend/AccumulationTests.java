package com.flunav.backend;

import com.flunav.backend.context.DatabaseContextHolder;
import com.flunav.backend.test.SimulationTestHarness;
import flunav.events.ChuteEmptyEvent;
import flunav.events.ItemCreatedEvent;
import flunav.types.LocationType;
import flunav.types.PositionType;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestConstructor;

import java.time.Instant;
import java.util.HashMap;

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
class AccumulationTests extends BaseIntegrationTest {

        private final SimulationTestHarness sim;

        AccumulationTests(SimulationTestHarness sim) {
                this.sim = sim;
        }

        @BeforeEach
        void setup() {
                System.setProperty("disable-sim-cleanup", "true");
                sim.reset();
        }

        @Test
        void testRecirculationOnFullChute() {
                DatabaseContextHolder.enterSimulationContext("test-sim");
                Instant start = Instant.parse("2026-02-07T10:00:00Z");
                sim.startAt(start);

                // start -> (conv1) -> junction
                // junction -> (conv_chute) -> chute (FULL)
                // junction -> (conv_main) -> end (RECIRCULATION)
                sim.createLocation("start", "Start", LocationType.GENERIC);
                sim.createLocation("junction", "Junction", LocationType.JUNCTION);

                // Create chute with capacity 1
                sim.createLocation("chute", "Chute", LocationType.CHUTE);

                sim.createLocation("end", "End", LocationType.GENERIC);

                sim.createConveyor("conv1", "start", "junction", 10.0, 1.0, false);
                sim.createConveyor("conv_chute", "junction", "chute", 10.0, 1.0, false);
                sim.createConveyor("conv_main", "junction", "end", 10.0, 1.0, true);

                // Make chute full by adding many items
                for (int i = 0; i < 101; i++) {
                        sim.applyEvent(new ItemCreatedEvent("filler-" + i, "Box", 1.0, true, "chute",
                                        PositionType.LOCATION, 0.0,
                                        new HashMap<>(), start));
                }

                // Create item that WANTS to go to chute
                sim.applyEvent(new ItemCreatedEvent("item-1", "Box", 1.0, true, "start", PositionType.LOCATION, 0.0,
                                new HashMap<>(), start));
                sim.applyEvent(new flunav.events.ItemDestinationEvent("item-1", "chute"));

                // Advance past junction (10s to reach junction)
                sim.advanceSeconds(15);

                var item = sim.getItem("item-1").orElseThrow();
                // Should have recirculated to conv_main
                assertEquals("conv_main", item.getCurrentEdgeId(),
                                "Item should have recirculated to main path because chute was full");
        }

        @Test
        void chuteEmptyDoesNotRestartUpstreamConveyorTravel() {
                DatabaseContextHolder.enterSimulationContext("test-sim");
                Instant start = Instant.parse("2026-02-07T11:00:00Z");
                sim.startAt(start);

                sim.createLocation("start", "Start", LocationType.JUNCTION);
                sim.createLocation("chute", "Chute", LocationType.CHUTE);
                sim.createConveyor("exit-conveyor", "start", "chute", 10.0, 1.0, false);

                sim.applyEvent(new ItemCreatedEvent("item-1", "Box", 1.0, true, "exit-conveyor",
                                PositionType.CONVEYOR, 0.0, new HashMap<>(), start));

                sim.advanceSeconds(5);
                sim.applyEvent(new ChuteEmptyEvent("chute", sim.getCurrentTime()));
                sim.advanceSeconds(6);

                var item = sim.getItem("item-1").orElseThrow();
                assertEquals("chute", item.getLocationId());
                assertNull(item.getCurrentEdgeId());
        }

        @Test

        void testStopAtStartOfStoppedConveyor() {

                DatabaseContextHolder.enterSimulationContext("test-sim");

                Instant start = Instant.parse("2026-02-07T12:00:00Z");

                sim.startAt(start);

                sim.createLocation("start", "Start", LocationType.GENERIC);

                sim.createLocation("end", "End", LocationType.GENERIC);

                sim.createConveyor("conv1", "start", "end", 10.0, 0.0, false); // STOPPED

                // Item enters conv1 at 50% progress (e.g. from a teleport or late event)

                sim.applyEvent(new ItemCreatedEvent("item-1", "Box", 1.0, true, "conv1", PositionType.CONVEYOR, 50.0,
                                new HashMap<>(), start));

                var item = sim.getItem("item-1").orElseThrow();

                assertEquals(0.5, item.getProgress(), 0.01, "Item entering stopped conveyor at 50% should stay there");

                // Now add item-2 at 20%

                sim.applyEvent(new ItemCreatedEvent("item-2", "Box", 1.0, true, "conv1", PositionType.CONVEYOR, 20.0,
                                new HashMap<>(), start.plusMillis(100)));

                assertEquals(0.2, sim.getItem("item-2").get().getProgress(), 0.01);

                // Now add item-3 at 80% (ahead of tail). It should STAY at 80% if we trust
                // external events.

                sim.applyEvent(new ItemCreatedEvent("item-3", "Box", 1.0, true, "conv1", PositionType.CONVEYOR, 80.0,
                                new HashMap<>(), start.plusMillis(200)));

                assertEquals(0.8, sim.getItem("item-3").get().getProgress(), 0.01,
                                "Item 3 should stay at 80% progress even if ahead of others on stopped conveyor");

        }

        @AfterEach
        void autoReset() {
                if (sim != null) {
                        sim.reset();
                }
        }
}
