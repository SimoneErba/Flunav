package com.flunav.backend;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.Duration;
import java.sql.DriverManager;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flunav.backend.context.DatabaseContextHolder;
import com.flunav.backend.domain.Role;
import com.flunav.backend.repositories.LiveConveyorRepository;
import com.flunav.backend.repositories.LiveItemRepository;
import com.flunav.backend.services.EventProcessor;
import com.flunav.backend.services.ClickHouseService;
import com.flunav.backend.services.GraphService;
import com.flunav.backend.services.HistoricalEventPlayer;
import com.flunav.backend.services.HistoricalGraphBuilder;
import com.flunav.backend.services.SimulationService;
import com.flunav.backend.services.TimeService;
import com.flunav.backend.utils.JwtUtils;

import flunav.events.LocationCreatedEvent;
import flunav.events.LocationDeletedEvent;
import flunav.events.ItemMovementCheckEvent;
import flunav.events.ItemPositionChangedEvent;
import flunav.types.LocationType;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "springwolf.enabled=false", "stale-item-cleanup.enabled=false",
        "state-recovery.enabled=false", "graph-snapshot.enabled=false",
        "simulation.capacity.min-free-memory-bytes=0"
})
@AutoConfigureMockMvc
class ConveyorSpacingDemoIntegrationTests extends BaseIntegrationTest {
    private static final String ENDPOINT = "/api/client-demo/conveyor-spacing";
    @Autowired MockMvc http;
    @Autowired ObjectMapper mapper;
    @Autowired JwtUtils jwt;
    @Autowired SimulationService simulations;
    @Autowired EventProcessor events;
    @Autowired ClickHouseService history;
    @Autowired GraphService graphs;
    @Autowired HistoricalEventPlayer playback;
    @Autowired HistoricalGraphBuilder snapshots;
    @Autowired TimeService time;
    @Autowired LiveItemRepository items;
    @Autowired LiveConveyorRepository conveyors;
    private String simulationId;
    private boolean liveSentinelCreated;
    private Instant start;

    @BeforeEach
    void setup() {
        start = Instant.ofEpochMilli(Instant.now().toEpochMilli());
        time.useFixedClock(start);
    }

    @AfterEach
    void cleanup() throws Exception {
        try {
            if (simulationId != null) simulations.destroySimulation(simulationId);
            if (liveSentinelCreated) {
                events.process(new LocationDeletedEvent("merge-demo-live-sentinel"), false).join();
            }
            history.flushAllEventsOrThrow();
            try (var connection = DriverManager.getConnection(CLICKHOUSE_CONTAINER.getJdbcUrl(),
                    CLICKHOUSE_CONTAINER.getUsername(), CLICKHOUSE_CONTAINER.getPassword());
                    var statement = connection.createStatement()) {
                statement.execute("TRUNCATE TABLE Events");
                statement.execute("TRUNCATE TABLE snapshots");
            }
        } finally {
            DatabaseContextHolder.clearSimulation();
            time.reset();
        }
    }

    @Test
    void adminLaunchesIsolatedMergeWithBeltStopRollerQueueAndAutomaticResume() throws Exception {
        events.process(new LocationCreatedEvent("merge-demo-live-sentinel", "Live", true, 0.0, 0.0,
                LocationType.JUNCTION, 100, Map.of()), false).join();
        liveSentinelCreated = true;
        String body = http.perform(post(ENDPOINT)
                .header("Authorization", "Bearer " + jwt.generateToken("merge-admin", Role.ADMIN)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("status").value("PLAYING"))
                .andReturn().getResponse().getContentAsString();
        simulationId = mapper.readTree(body).get("id").asText();
        simulations.pauseSimulation(simulationId);
        assertNull(DatabaseContextHolder.getSimulationId());
        assertTrue(graphs.getGraphData(start, false, null, false).getLocations().stream()
                .anyMatch(location -> "merge-demo-live-sentinel".equals(location.getId())));
        assertTrue(graphs.getGraphData(start, false, null, false).getItems().stream()
                .noneMatch(item -> item.getId().startsWith("CS-")));
        var graph = graphs.getGraphData(start, false, simulationId, false);
        assertEquals(4, graph.getLocations().size());
        assertEquals(3, graph.getConveyors().size());
        assertEquals(9, graph.getItems().size());
        var scheduled = simulations.getSimulationState(simulationId).getScheduledEventsByItem();
        for (int index = 1; index <= 4; index++) {
            assertEquals(Duration.ofMillis(500), Duration.between(
                    scheduled.get("CS-BELT-" + index).getTimestamp(),
                    scheduled.get("CS-ROLLER-" + index).getTimestamp()));
        }
        try (var database = DatabaseContextHolder.enterSimulationContext(simulationId)) {
            simulations.processEventsUntil(simulationId, start.plusSeconds(1));
            assertTrue(conveyors.isFlowStopped("Conveyor_CS-BELT-SOURCE_CS-MERGE"));
            assertFalse(conveyors.isFlowStopped("Conveyor_CS-ROLLER-SOURCE_CS-MERGE"));
            assertTrue(items.getItemState("CS-BELT-2").isFlowPaused());
            assertFalse(items.getItemState("CS-ROLLER-2").isFlowPaused());
            simulations.processEventsUntil(simulationId, start.plusSeconds(3));
            assertTrue(items.getItemState("CS-ROLLER-2").isFlowPaused());
            simulations.processEventsUntil(simulationId, start.plusSeconds(90));
            assertFalse(conveyors.isFlowStopped("Conveyor_CS-BELT-SOURCE_CS-MERGE"));
            for (int index = 1; index <= 4; index++) {
                assertEquals("CS-EXIT", items.getItemState("CS-BELT-" + index).getPositionId());
                assertEquals("CS-EXIT", items.getItemState("CS-ROLLER-" + index).getPositionId());
            }
        }
    }

    @Test
    void playbackKeepsBlockedItemsAtTheirPhysicalCheckpointsAndDrainsTheMerge() throws Exception {
        String body = http.perform(post(ENDPOINT)
                .header("Authorization", "Bearer " + jwt.generateToken("merge-admin", Role.ADMIN)))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        simulationId = mapper.readTree(body).get("id").asText();
        simulations.pauseSimulation(simulationId);
        var state = simulations.getSimulationState(simulationId);
        try (var database = DatabaseContextHolder.enterSimulationContext(simulationId)) {
            playback.advanceThrough(state, start.plusSeconds(1));
            assertTrue(items.getItemState("CS-BELT-2").isFlowPaused());
            double beltCheckpoint = items.getItemState("CS-BELT-2").getAccumulatedDistance();
            assertEquals(70.0, beltCheckpoint, 0.02);
            playback.advanceThrough(state, start.plusSeconds(2));
            assertEquals(beltCheckpoint, items.getItemState("CS-BELT-2").getAccumulatedDistance(), 1e-9);
            playback.advanceThrough(state, start.plusSeconds(3));
            assertTrue(items.getItemState("CS-ROLLER-2").isFlowPaused());
            double rollerCheckpoint = items.getItemState("CS-ROLLER-2").getAccumulatedDistance();
            assertEquals(95.0 - 0.25 / 3.0 * 100.0, rollerCheckpoint, 0.02);
            playback.advanceThrough(state, start.plusSeconds(4));
            assertEquals(rollerCheckpoint, items.getItemState("CS-ROLLER-2").getAccumulatedDistance(), 1e-9);
            for (int second = 5; second <= 90; second++) {
                playback.advanceThrough(state, start.plusSeconds(second));
            }
            for (int index = 1; index <= 4; index++) {
                assertEquals("CS-EXIT", items.getItemState("CS-BELT-" + index).getPositionId());
                assertEquals("CS-EXIT", items.getItemState("CS-ROLLER-" + index).getPositionId());
            }
            assertEquals("CS-EXIT", items.getItemState("CS-OUTLET-1").getPositionId());
        }
    }

    @Test
    void mergeAdmitsTheEarlierArrivalAndMaintainsRollerSpacingThroughoutPlayback() throws Exception {
        String body = http.perform(post(ENDPOINT)
                .header("Authorization", "Bearer " + jwt.generateToken("merge-admin", Role.ADMIN)))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        simulationId = mapper.readTree(body).get("id").asText();
        simulations.pauseSimulation(simulationId);
        var state = simulations.getSimulationState(simulationId);
        try (var database = DatabaseContextHolder.enterSimulationContext(simulationId)) {
            for (int tick = 1; tick <= 360; tick++) {
                playback.advanceThrough(state, start.plusMillis(tick * 250L));
                for (String feeder : java.util.List.of("Conveyor_CS-ROLLER-SOURCE_CS-MERGE",
                        "Conveyor_CS-BELT-SOURCE_CS-MERGE", "Conveyor_CS-MERGE_CS-EXIT")) {
                    double length = feeder.equals("Conveyor_CS-MERGE_CS-EXIT") ? 1.5 : 3.0;
                    var occupants = conveyors.getItemsOrderedByDistance(feeder).stream()
                            .map(items::getItemState)
                            .sorted(java.util.Comparator.comparingDouble(item -> -item.getAccumulatedDistance()))
                            .toList();
                    for (int index = 1; index < occupants.size(); index++) {
                        double gap = (occupants.get(index - 1).getAccumulatedDistance()
                                - occupants.get(index).getAccumulatedDistance()) / 100.0 * length;
                        assertTrue(gap >= 0.249, "Overlapping " + occupants.get(index - 1).getId()
                                + " and " + occupants.get(index).getId() + " at " + tick * 250L
                                + " ms on " + feeder + ": gap=" + gap);
                    }
                }
                if (tick == 43) {
                    assertEquals("Conveyor_CS-MERGE_CS-EXIT", items.getItemState("CS-ROLLER-1").getPositionId(),
                            "The roller leader was waiting before the second belt item reached the merge");
                    assertEquals("Conveyor_CS-BELT-SOURCE_CS-MERGE", items.getItemState("CS-BELT-2").getPositionId());
                }
            }
        }
    }

    @Test
    void snapshotRestoresTheReservedApproachAsAnAdmissionCheck() throws Exception {
        String body = http.perform(post(ENDPOINT)
                .header("Authorization", "Bearer " + jwt.generateToken("merge-admin", Role.ADMIN)))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        simulationId = mapper.readTree(body).get("id").asText();
        simulations.pauseSimulation(simulationId);
        playback.advanceThrough(simulations.getSimulationState(simulationId), start.plusMillis(5100));
        var baseline = graphs.getGraphData(start.plusMillis(5100), false, simulationId, false);
        var fork = simulations.createWhatIf(null);
        try {
            simulations.isolateFromLiveInput(fork.getId());
            try (var database = DatabaseContextHolder.enterSimulationContext(fork.getId())) {
                snapshots.restoreFromSnapshotData(baseline);
                assertInstanceOf(ItemMovementCheckEvent.class, fork.getScheduledEventsByItem().get("CS-BELT-1"));
                assertNotNull(items.getItemState("CS-BELT-1").getPlannedTransitionTimestamp());
                events.process(new ItemPositionChangedEvent("CS-OUTLET-1", "Conveyor_CS-MERGE_CS-EXIT",
                        0.0, start.plusMillis(5200)), false).join();
                playback.advanceThrough(fork, start.plusMillis(5400));
                assertEquals("Conveyor_CS-BELT-SOURCE_CS-MERGE", items.getItemState("CS-BELT-1").getPositionId(),
                        "A restored reservation must still check outlet spacing before transferring");
            }
            try (var database = DatabaseContextHolder.enterSimulationContext(simulationId)) {
                assertEquals("Conveyor_CS-BELT-SOURCE_CS-MERGE", items.getItemState("CS-BELT-1").getPositionId());
            }
        } finally {
            simulations.destroySimulation(fork.getId());
        }
    }

    @Test
    void rejectsAnonymousAndViewerLaunches() throws Exception {
        http.perform(post(ENDPOINT)).andExpect(status().isUnauthorized());
        http.perform(post(ENDPOINT)
                .header("Authorization", "Bearer " + jwt.generateToken("merge-viewer", Role.VIEWER)))
                .andExpect(status().isForbidden());
    }

    @Test
    void openApiIncludesMergeLauncher() throws Exception {
        String contract = http.perform(get("/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertEquals("startConveyorSpacing", mapper.readTree(contract)
                .path("paths").path(ENDPOINT).path("post").path("operationId").asText());
        String output = System.getProperty("flunav.openapi-output");
        if (output != null) Files.writeString(Path.of(output), contract);
    }
}
