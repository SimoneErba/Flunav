package com.flunav.backend;

import com.flunav.backend.context.DatabaseContextHolder;
import com.flunav.backend.domain.Conveyor;
import com.flunav.backend.repositories.LiveConveyorRepository;
import com.flunav.backend.services.OrientDBService;
import com.flunav.backend.test.SimulationTestHarness;
import flunav.events.ConnectionSpeedChangedEvent;
import flunav.events.ItemCreatedEvent;
import flunav.events.ItemDestinationEvent;
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
class AccumulationTests extends BaseIntegrationTest {

        @Autowired
        private SimulationTestHarness sim;

        @Autowired
        private com.flunav.backend.services.PathfindingService pathfindingService;

        @MockBean
        private org.springframework.amqp.core.AmqpTemplate amqpTemplate;

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
                sim.stubLocation("start", "Start", LocationType.GENERIC);
                sim.stubLocation("junction", "Junction", LocationType.JUNCTION);

                // Stub chute with capacity 1
                com.flunav.backend.domain.Location chute = new com.flunav.backend.domain.Location("chute", "Chute",
                                LocationType.CHUTE, true, new HashMap<>(), 0.0, 0.0, 1);
                sim.stubLocation("chute", "Chute", LocationType.CHUTE);

                sim.stubLocation("end", "End", LocationType.GENERIC);

                sim.stubConveyor("conv1", "start", "junction", 10.0, 1.0);
                sim.stubConveyor("conv_chute", "junction", "chute", 10.0, 1.0);
                sim.stubConveyor("conv_main", "junction", "end", 10.0, 1.0);
                sim.getConveyor("conv_main").setMainPath(true);

                // Make chute full by adding many items
                for (int i = 0; i < 101; i++) {
                        sim.applyEvent(new ItemCreatedEvent("filler-" + i, "Box", 1.0, true, "chute",
                                        PositionType.LOCATION, 0.0,
                                        new HashMap<>(), start));
                }

                // Create item that WANTS to go to chute
                sim.applyEvent(new ItemCreatedEvent("item-1", "Box", 1.0, true, "conv1", PositionType.CONVEYOR, 0.0,
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

        void testStopAtStartOfStoppedConveyor() {

                DatabaseContextHolder.enterSimulationContext("test-sim");

                Instant start = Instant.parse("2026-02-07T12:00:00Z");

                sim.startAt(start);

                sim.stubLocation("start", "Start", LocationType.GENERIC);

                sim.stubLocation("end", "End", LocationType.GENERIC);

                sim.stubConveyor("conv1", "start", "end", 10.0, 0.0); // STOPPED

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