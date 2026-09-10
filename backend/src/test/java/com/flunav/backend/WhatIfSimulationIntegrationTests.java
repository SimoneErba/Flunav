package com.flunav.backend;

import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flunav.backend.context.DatabaseContextHolder;
import com.flunav.backend.models.graph.GraphData;
import com.flunav.backend.models.simulation.*;
import com.flunav.backend.repositories.LiveSimulationRepository;
import com.flunav.backend.services.*;
import com.flunav.backend.utils.ControllerHelper;
import flunav.events.*;
import flunav.types.ConveyorType;
import flunav.types.LocationType;
import flunav.types.PositionType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "springwolf.enabled=false", "rabbitmq.routing-key.item-events=1", "stale-item-cleanup.enabled=false",
        "state-recovery.enabled=false", "graph-snapshot.enabled=false", "simulation.manage-logic=true",
        "simulation.capacity.max-active=3", "simulation.capacity.min-free-memory-bytes=0"
})
@AutoConfigureMockMvc
class WhatIfSimulationIntegrationTests extends BaseIntegrationTest {
    @Autowired SimulationService simulations;
    @Autowired SimulationInputService intake;
    @Autowired HistoricalEventPlayer player;
    @Autowired EventProcessor events;
    @Autowired GraphService graphs;
    @Autowired ClickHouseService history;
    @Autowired OrientDBService orient;
    @Autowired LiveSystemScheduler scheduler;
    @Autowired LiveSimulationRepository metadata;
    @Autowired StringRedisTemplate redis;
    @Autowired TimeService time;
    @Autowired ControllerHelper mutations;
    @Autowired MockMvc http;
    @Autowired ObjectMapper mapper;
    private Instant now;

    @BeforeEach
    void setup() throws Exception {
        System.setProperty("disable-sim-cleanup", "true");
        reset();
        now = Instant.now();
        time.useFixedClock(now);
        events.process(new LocationCreatedEvent("entry", "Entry", true, 0.0, 0.0, LocationType.GENERIC,
                100, new HashMap<>()), false).join();
        events.process(new LocationCreatedEvent("exit", "Exit", true, 1.0, 0.0, LocationType.CHUTE,
                100, new HashMap<>()), false).join();
        events.process(new ConnectionCreatedEvent("belt", "entry", "exit", 100.0, 0.0, 0.0, null, true,
                "Belt", true, ConveyorType.BELT, 100, new HashMap<>()), false).join();
        events.process(item("original", now), false).join();
    }

    @AfterEach
    void cleanup() throws Exception {
        reset();
        System.clearProperty("disable-sim-cleanup");
    }

    private void reset() throws Exception {
        DatabaseContextHolder.clearSimulation();
        scheduler.cancelAll();
        for (var state : metadata.getAllSimulationStates().stream()
                .sorted(java.util.Comparator.comparing(state -> state.sourceSimulationId() == null ? 1 : 0)).toList()) {
            simulations.destroySimulation(state.simulationId());
        }
        history.flushAllEventsOrThrow();
        try (var connection = DriverManager.getConnection(CLICKHOUSE_CONTAINER.getJdbcUrl(),
                CLICKHOUSE_CONTAINER.getUsername(), CLICKHOUSE_CONTAINER.getPassword());
                var statement = connection.createStatement()) {
            statement.execute("TRUNCATE TABLE Events");
            statement.execute("TRUNCATE TABLE snapshots");
        }
        orient.withSession(session -> {
            session.command("DELETE FROM Conveyor UNSAFE").close();
            session.command("DELETE FROM Item UNSAFE").close();
            session.command("DELETE FROM Location UNSAFE").close();
        });
        try (var connection = Objects.requireNonNull(redis.getConnectionFactory()).getConnection()) {
            connection.serverCommands().flushAll();
        }
        time.reset();
    }

    @Test
    void liveForkStartsPausedWithObservedPositionsAndIsolatedTopologyAndMovement() {
        SimulationState branch = simulations.createWhatIf(null);
        assertEquals(SimulationKind.WHAT_IF_LIVE, branch.getKind());
        assertEquals(SimulationStatus.PAUSED, branch.getStatus());
        assertEquals(now, branch.getForkTimestamp());
        assertEquals(now, branch.getLiveHandoffTimestamp());
        assertEquals(0.25, graph(branch).getItems().getFirst().getProgress(), 0.001);
        assertEquals(SimulationKind.WHAT_IF_LIVE, metadata.getState(branch.getId()).orElseThrow().kind());
        try (var context = DatabaseContextHolder.enterSimulationContext(branch.getId())) {
            mutations.processAndLogEvent(new ConnectionSpeedChangedEvent("belt", 2.0)).join();
        }
        assertEquals(0.0, graphs.getGraphData(now, false).getConveyors().getFirst().getSpeed());
        assertEquals(2.0, graph(branch).getConveyors().getFirst().getSpeed());
        player.advanceThrough(branch, now.plusSeconds(40));
        assertEquals("exit", graph(branch).getItems().getFirst().getLocationId());
        assertEquals("belt", graphs.getGraphData(now, false).getItems().getFirst().getCurrentEdgeId());
        assertEquals(LiveInputState.FROZEN, branch.getLiveInputState());
        assertFalse(history.getEventsBetween(now.minusSeconds(1), now.plusSeconds(50)).stream()
                .anyMatch(event -> event instanceof ConnectionSpeedChangedEvent));
    }

    @Test
    void liveHandoffAcceptsUnflushedItemsOnceAndRejectsObservedAndLiveScheduledMovement() {
        SimulationState branch = simulations.createWhatIf(null);
        Instant later = now.plusSeconds(1);
        time.useFixedClock(later);
        ItemCreatedEvent created = item("unflushed", later);
        events.process(created, false).join();
        events.process(new ItemPositionChangedEvent("original", "exit", 100.0, later), false).join();
        events.process(new ItemPositionDeletedEvent("original"), false).join();
        events.process(item("scheduled-only", later), false, EventOrigin.LIVE_SCHEDULED).join();
        intake.addHistory(branch.getId(), List.of(created));
        player.advanceThrough(branch, later);
        GraphData result = graph(branch);
        assertEquals(2, result.getItems().size());
        assertEquals(1, result.getItems().stream().filter(item -> "unflushed".equals(item.getId())).count());
        assertEquals("belt", result.getItems().stream().filter(item -> "original".equals(item.getId()))
                .findFirst().orElseThrow().getCurrentEdgeId());
        assertNull(intake.peek(branch.getId()));
    }

    @Test
    void concurrentLiveCreationIsEitherInTheBaselineOrInTheDirectIntake() {
        var pending = IntStream.range(0, 8)
                .mapToObj(index -> events.process(item("racing-" + index, now), false)).toList();
        SimulationState branch = simulations.createWhatIf(null);
        CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new)).join();
        player.advanceThrough(branch, now);
        assertEquals(9, graph(branch).getItems().size());
        assertEquals(9, graph(branch).getItems().stream().map(item -> item.getId()).distinct().count());
    }

    @Test
    void liveIntakePreservesReducerOrderForEventsWithTheSameTimestamp() throws Exception {
        SimulationState branch = simulations.createWhatIf(null);
        Instant later = now.plusSeconds(1);
        time.useFixedClock(later);
        events.process(item("same-time", later), false).join();
        for (String name : List.of("First name", "Final name")) {
            com.fasterxml.jackson.databind.node.ObjectNode payload = mapper.valueToTree(new ItemRenamedEvent("same-time", name));
            payload.put("timestamp", later.toString());
            events.process(mapper.treeToValue(payload, DomainEvent.class), false).join();
        }
        player.advanceThrough(branch, later);
        assertEquals("Final name", graph(branch).getItems().stream().filter(item -> "same-time".equals(item.getId()))
                .findFirst().orElseThrow().getName());
    }

    @Test
    void historicalForkUsesExactPausedSourceClockAndKeepsSourceAliveOnExit() throws Exception {
        Instant anchor = now.minusSeconds(30);
        history.saveSnapshot(java.util.UUID.randomUUID().toString(), anchor, graphs.getGraphData(anchor, false));
        SimulationState source = simulations.createSimulation(anchor);
        awaitReady(source);
        simulations.startPlayback(source.getId(), 1.0);
        Thread.sleep(150);
        SimulationState branch = simulations.createWhatIf(source.getId());
        assertEquals(SimulationKind.WHAT_IF_SIMULATION, branch.getKind());
        assertEquals(SimulationStatus.PAUSED, source.getStatus());
        assertEquals(simulations.getSimulationClock(source), branch.getForkTimestamp());
        assertThrows(ResponseStatusException.class, () -> simulations.startPlayback(source.getId(), 1.0));
        assertThrows(ResponseStatusException.class, () -> simulations.destroySimulation(source.getId()));
        try (var context = DatabaseContextHolder.enterSimulationContext(branch.getId())) {
            mutations.processAndLogEvent(new ItemRenamedEvent("original", "Scenario item")).join();
        }
        assertEquals("Scenario item", graph(branch).getItems().getFirst().getName());
        assertEquals("original", graph(source).getItems().getFirst().getName());
        Instant fork = branch.getForkTimestamp();
        simulations.updateHeartbeat(branch.getId());
        simulations.destroySimulation(branch.getId());
        assertTrue(metadata.exists(source.getId()));
        assertEquals(fork, simulations.getSimulationClock(source));
        assertTrue(redis.keys("sim:" + branch.getId() + ":*").isEmpty());
        assertFalse(intake.isRegistered(branch.getId()));
    }

    @Test
    void futureCutoffRemainsFrozenWhenPhysicalTimeCatchesUp() {
        SimulationState branch = simulations.createWhatIf(null);
        player.advanceThrough(branch, now.plusSeconds(2));
        time.useFixedClock(now.plusSeconds(10));
        events.process(item("too-late", now.plusSeconds(3)), false).join();
        simulations.ensureLiveHandoff(branch);
        player.advanceThrough(branch, now.plusSeconds(4));
        assertEquals(LiveInputState.FROZEN, branch.getLiveInputState());
        assertEquals(LiveInputState.FROZEN, metadata.getState(branch.getId()).orElseThrow().liveInputState());
        assertEquals(1, graph(branch).getItems().size());
    }

    @Test
    void manualMutationUsesVirtualTimestampAndRebuildsItsSchedule() {
        SimulationState branch = simulations.createWhatIf(null);
        time.useFixedClock(now.plusSeconds(100));
        try (var context = DatabaseContextHolder.enterSimulationContext(branch.getId())) {
            mutations.processAndLogEvent(new ConnectionSpeedChangedEvent("belt", 3.0)).join();
        }
        DomainEvent arrival = branch.getScheduledEventsByItem().get("original");
        assertNotNull(arrival);
        assertEquals(now.plusSeconds(25), arrival.getTimestamp());
        assertEquals(now, simulations.getSimulationClock(branch));
        assertEquals(now.plusSeconds(100), time.now());
        assertNull(DatabaseContextHolder.getSimulationId());
    }

    @Test
    void capacityFailureLeavesExistingBranchesAndLiveStateIntact() {
        simulations.createWhatIf(null);
        simulations.createWhatIf(null);
        simulations.createWhatIf(null);
        ResponseStatusException failure = assertThrows(ResponseStatusException.class, () -> simulations.createWhatIf(null));
        assertEquals(429, failure.getStatusCode().value());
        assertEquals(3, metadata.getAllSimulationStates().size());
        assertEquals(1, graphs.getGraphData(now, false).getItems().size());
    }

    @Test
    void liveDerivedPlaybackPausesResumesAndAcceptsItemsQueuedWhilePaused() throws Exception {
        SimulationState branch = simulations.createWhatIf(null);
        time.reset();
        simulations.startPlayback(branch.getId(), 1.0);
        Thread.sleep(200);
        simulations.pauseSimulation(branch.getId());
        Instant pausedAt = simulations.getSimulationClock(branch);
        Thread.sleep(100);
        assertEquals(pausedAt, simulations.getSimulationClock(branch));
        events.process(item("while-paused", time.physicalNow()), false).join();
        simulations.startPlayback(branch.getId(), 50.0);
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (branch.getLiveInputState() != LiveInputState.FROZEN && System.nanoTime() < deadline) Thread.sleep(25);
        simulations.pauseSimulation(branch.getId());
        assertEquals(SimulationStatus.PAUSED, branch.getStatus());
        assertEquals(LiveInputState.FROZEN, branch.getLiveInputState());
        assertTrue(graph(branch).getItems().stream().anyMatch(item -> "while-paused".equals(item.getId())));
    }

    @Test
    void futureScenarioContinuesThroughAnomalyBaselineEvaluationAfterCompletingMovement() {
        SimulationState branch = simulations.createWhatIf(null);
        try (var context = DatabaseContextHolder.enterSimulationContext(branch.getId())) {
            mutations.processAndLogEvent(new ConnectionSpeedChangedEvent("belt", 3.0)).join();
        }
        simulations.initializeAnomalySchedule(branch.getId(), now);
        player.advanceThrough(branch, now.plusSeconds(610));
        assertEquals(now.plusSeconds(610), simulations.getSimulationClock(branch));
        assertEquals(LiveInputState.FROZEN, branch.getLiveInputState());
        assertEquals("exit", graph(branch).getItems().getFirst().getLocationId());
    }

    @Test
    void whatIfEndpointRequiresAuthenticationAndReturnsBranchMetadata() throws Exception {
        http.perform(post("/api/simulations/what-if").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        String login = http.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"Flun4v!\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String token = mapper.readTree(login).get("token").asText();
        http.perform(post("/api/simulations/what-if").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("kind").value("WHAT_IF_LIVE"))
                .andExpect(jsonPath("status").value("PAUSED"))
                .andExpect(jsonPath("liveInputState").value("ACTIVE"));
    }

    private ItemCreatedEvent item(String id, Instant timestamp) {
        return new ItemCreatedEvent(id, id, 1.0, true, "belt", PositionType.CONVEYOR, 25.0,
                new HashMap<>(), timestamp);
    }

    private GraphData graph(SimulationState state) {
        return graphs.getGraphData(simulations.getSimulationClock(state), false, state.getId(), false);
    }

    private void awaitReady(SimulationState state) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (state.getStatus() != SimulationStatus.READY && System.nanoTime() < deadline) Thread.sleep(25);
        assertEquals(SimulationStatus.READY, state.getStatus());
    }
}
