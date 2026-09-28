package com.flunav.backend;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
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
import com.flunav.backend.services.SimulationService;
import com.flunav.backend.services.TimeService;
import com.flunav.backend.utils.JwtUtils;

import flunav.events.LocationCreatedEvent;
import flunav.events.LocationDeletedEvent;
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
        try (var database = DatabaseContextHolder.enterSimulationContext(simulationId)) {
            simulations.processEventsUntil(simulationId, start.plusSeconds(1));
            assertTrue(conveyors.isFlowStopped("Conveyor_CS-BELT-SOURCE_CS-MERGE"));
            assertFalse(conveyors.isFlowStopped("Conveyor_CS-ROLLER-SOURCE_CS-MERGE"));
            assertTrue(items.getItemState("CS-BELT-2").isFlowPaused());
            assertFalse(items.getItemState("CS-ROLLER-2").isFlowPaused());
            simulations.processEventsUntil(simulationId, start.plusSeconds(2));
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
