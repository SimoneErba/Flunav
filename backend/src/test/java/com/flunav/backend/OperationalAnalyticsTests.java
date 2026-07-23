package com.flunav.backend;

import com.flunav.backend.context.DatabaseContextHolder;
import com.flunav.backend.controllers.AnalyticsController;
import com.flunav.backend.models.analytics.ConnectionStateSignal;
import com.flunav.backend.models.analytics.ExitCandidate;
import com.flunav.backend.repositories.LiveSimulationRepository;
import com.flunav.backend.services.ClickHouseService;
import com.flunav.backend.services.OperationalAnalyticsService;
import com.flunav.backend.models.simulation.SimulationStatus;

import flunav.events.ItemCreatedEvent;
import flunav.types.PositionType;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestConstructor;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest(properties = {
        "springwolf.enabled=false",
        "state-recovery.enabled=false",
        "graph-snapshot.enabled=false"
})
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class OperationalAnalyticsTests extends BaseIntegrationTest {
    private final ClickHouseService clickHouseService;
    private final OperationalAnalyticsService operationalAnalyticsService;
    private final LiveSimulationRepository liveSimulationRepository;
    private final AnalyticsController analyticsController;

    OperationalAnalyticsTests(
            ClickHouseService clickHouseService,
            OperationalAnalyticsService operationalAnalyticsService,
            LiveSimulationRepository liveSimulationRepository,
            AnalyticsController analyticsController) {
        this.clickHouseService = clickHouseService;
        this.operationalAnalyticsService = operationalAnalyticsService;
        this.liveSimulationRepository = liveSimulationRepository;
        this.analyticsController = analyticsController;
    }

    @Test
    void exitProjectionRetriesAndUsesLatestPrecedingCreationForReusedItemId() {
        Instant firstCreation = Instant.parse("2042-01-01T00:00:00Z");
        Instant latestCreation = firstCreation.plusSeconds(10);
        Instant exit = firstCreation.plusSeconds(25);
        String itemId = "operational-reused-item";
        ExitCandidate candidate = new ExitCandidate(
                "operational-exit-candidate",
                "operational-exit-event",
                itemId,
                "operational-chute",
                exit,
                "live",
                null,
                exit);

        clickHouseService.saveExitCandidateAsync(candidate);
        clickHouseService.flushExitCandidates();
        clickHouseService.projectCompletedJourneys();
        assertEquals(0, summary(firstCreation, exit.plusSeconds(1)).completedItemCount());

        clickHouseService.saveEventAsync(new ItemCreatedEvent(
                itemId, "First", 1.0, true, "start", PositionType.LOCATION, 0.0, Map.of(), firstCreation));
        clickHouseService.saveEventAsync(new ItemCreatedEvent(
                itemId, "Second", 1.0, true, "start", PositionType.LOCATION, 0.0, Map.of(), latestCreation));
        clickHouseService.flushEvents();
        clickHouseService.projectCompletedJourneys();

        var summary = summary(firstCreation, exit.plusSeconds(1));
        assertEquals(1, summary.completedItemCount());
        assertEquals(15_000, summary.averageTraversalMillis());
        assertEquals(15_000, summary.p50TraversalMillis());
        assertEquals(15_000, summary.p99TraversalMillis());
    }

    @Test
    void changedAssignmentsAreDeduplicatedAndAttachedToCompletedJourney() {
        Instant created = Instant.parse("2043-01-01T00:00:00Z");
        Instant exit = created.plusSeconds(40);
        String itemId = "operational-recirculated-item";
        clickHouseService.saveEventAsync(new ItemCreatedEvent(
                itemId, "Rerouted", 1.0, true, "start", PositionType.LOCATION, 0.0, Map.of(), created));
        clickHouseService.flushEvents();
        clickHouseService.saveExitCandidateAsync(new ExitCandidate(
                "operational-recirculated-exit", "exit-event", itemId, "chute", exit,
                "live", null, exit));
        clickHouseService.flushExitCandidates();
        clickHouseService.projectCompletedJourneys();

        operationalAnalyticsService.recordRecirculation(
                itemId, null, List.of("start", "a"), created.plusSeconds(5));
        operationalAnalyticsService.recordRecirculation(
                itemId, List.of("start", "a"), List.of("start", "a"), created.plusSeconds(10));
        operationalAnalyticsService.recordRecirculation(
                itemId, List.of("start", "a"), List.of("start", "b"), created.plusSeconds(20));
        operationalAnalyticsService.recordRecirculation(
                itemId, List.of("start", "a"), List.of("start", "b"), created.plusSeconds(20));
        clickHouseService.flushRecirculationFacts();

        var summary = summary(created, exit.plusSeconds(1));
        assertEquals(1, summary.recirculationEventCount());
        assertEquals(1, summary.completedJourneysWithRecirculation());
        assertEquals(100.0, summary.completedJourneysWithRecirculationPercentage());
    }

    @Test
    void knownJourneyDatasetProducesExactAverageAndPercentiles() {
        Instant windowStart = Instant.parse("2046-01-01T00:00:00Z");
        for (int index = 1; index <= 100; index++) {
            String itemId = "operational-quantile-item-" + index;
            Instant exit = windowStart.plusSeconds(1_000 + index);
            Instant created = exit.minusSeconds(index);
            clickHouseService.saveEventAsync(new ItemCreatedEvent(
                    itemId, "Quantile", 1.0, true, "start", PositionType.LOCATION, 0.0, Map.of(), created));
            clickHouseService.saveExitCandidateAsync(new ExitCandidate(
                    "operational-quantile-exit-" + index,
                    "operational-quantile-event-" + index,
                    itemId,
                    "quantile-chute",
                    exit,
                    "live",
                    null,
                    exit));
        }
        clickHouseService.flushEvents();
        clickHouseService.flushExitCandidates();
        clickHouseService.projectCompletedJourneys();

        var summary = summary(windowStart, windowStart.plusSeconds(2_000));
        assertEquals(100, summary.completedItemCount());
        assertEquals(50_500.0, summary.averageTraversalMillis());
        assertEquals(51_000, summary.p50TraversalMillis());
        assertEquals(91_000, summary.p90TraversalMillis());
        assertEquals(96_000, summary.p95TraversalMillis());
        assertEquals(100_000, summary.p99TraversalMillis());
    }

    @Test
    void conveyorStopStateMachineCombinesMixedCausesAndClipsOpenIntervals() {
        String simulationId = "operational-stop-simulation";
        Instant restore = Instant.parse("2044-01-01T00:00:00Z");
        Instant start = restore.plusSeconds(1);
        saveSimulation(simulationId, restore, start.plusSeconds(100));

        saveSignal(simulationId, "create", start, "CONNECTION_CREATED", true, 1.0);
        saveSignal(simulationId, "deactivate", start.plusSeconds(10), "CONNECTION_DEACTIVATED", false, null);
        saveSignal(simulationId, "zero", start.plusSeconds(20), "CONNECTION_SPEED_CHANGED", null, 0.0);
        saveSignal(simulationId, "activate", start.plusSeconds(30), "CONNECTION_ACTIVATED", true, null);
        saveSignal(simulationId, "resume", start.plusSeconds(50), "CONNECTION_SPEED_CHANGED", null, 2.0);
        saveSignal(simulationId, "stop-2", start.plusSeconds(60), "CONNECTION_DEACTIVATED", false, null);
        saveSignal(simulationId, "stop-2-repeat", start.plusSeconds(65), "CONNECTION_DEACTIVATED", false, null);
        saveSignal(simulationId, "resume-2", start.plusSeconds(80), "CONNECTION_ACTIVATED", true, null);
        saveSignal(simulationId, "open", start.plusSeconds(90), "CONNECTION_SPEED_CHANGED", null, 0.0);
        clickHouseService.flushSimulationConnectionSignals();

        try (var ignored = DatabaseContextHolder.enterSimulationContext(simulationId)) {
            var metric = operationalAnalyticsService.getConveyorStops(start, start.plusSeconds(100)).join().stream()
                    .filter(row -> "operational-conveyor".equals(row.conveyorId()))
                    .findFirst()
                    .orElseThrow();
            assertEquals(3, metric.overlappingStopCount());
            assertEquals(70_000, metric.totalStoppedMillis());
            assertEquals(40_000, metric.maximumStoppedMillis());
            assertEquals(30.0, metric.availabilityPercentage());
            assertTrue(metric.currentlyStopped());
            assertEquals(start.plusSeconds(90), metric.stopStartedAt());
        }
    }

    @Test
    void simulationSummaryMergesLiveHistoryWithPostRestoreFactsAndSupportsSimulationOnlyCreation() {
        String simulationId = "operational-journey-simulation";
        Instant created = Instant.parse("2047-01-01T00:00:00Z");
        Instant restore = created.plusSeconds(20);
        Instant exit = restore.plusSeconds(20);
        saveSimulation(simulationId, restore, exit);

        clickHouseService.saveEventAsync(new ItemCreatedEvent(
                "cross-boundary-item", "Cross boundary", 1.0, true,
                "start", PositionType.LOCATION, 0.0, Map.of(), created));
        clickHouseService.flushEvents();
        operationalAnalyticsService.recordRecirculation(
                "cross-boundary-item", List.of("a"), List.of("b"), restore.minusSeconds(5));

        try (var ignored = DatabaseContextHolder.enterSimulationContext(simulationId)) {
            operationalAnalyticsService.recordRecirculation(
                    "cross-boundary-item", List.of("b"), List.of("c"), restore.plusSeconds(5));
        }
        clickHouseService.saveExitCandidateAsync(new ExitCandidate(
                "cross-boundary-exit", "cross-boundary-event", "cross-boundary-item", "chute",
                exit, simulationId, null, restore));
        clickHouseService.saveExitCandidateAsync(new ExitCandidate(
                "simulation-only-exit", "simulation-only-event", "simulation-only-item", "chute",
                exit.plusSeconds(5), simulationId, restore.plusSeconds(2), restore));
        clickHouseService.flushRecirculationFacts();
        clickHouseService.flushExitCandidates();
        clickHouseService.projectCompletedJourneys();

        try (var ignored = DatabaseContextHolder.enterSimulationContext(simulationId)) {
            var summary = operationalAnalyticsService.getJourneySummary(
                    created, exit.plusSeconds(10)).join();
            assertEquals(2, summary.completedItemCount());
            assertEquals(2, summary.recirculationEventCount());
            assertEquals(1, summary.completedJourneysWithRecirculation());
        }
    }

    @Test
    void clickHouseJourneySummaryExcludesLivePostRestoreCompletionsFromSimulationContext() {
        String simulationId = "operational-clickhouse-simulation";
        Instant created = Instant.parse("2049-01-01T00:00:00Z");
        Instant restore = created.plusSeconds(20);
        Instant beforeRestoreExit = restore.minusSeconds(5);
        Instant afterRestoreLiveExit = restore.plusSeconds(10);
        Instant simulationExit = restore.plusSeconds(15);
        saveSimulation(simulationId, restore, simulationExit);

        clickHouseService.saveEventAsync(new ItemCreatedEvent(
                "live-before-restore-item", "Live before restore", 1.0, true,
                "start", PositionType.LOCATION, 0.0, Map.of(), created));
        clickHouseService.saveEventAsync(new ItemCreatedEvent(
                "live-after-restore-item", "Live after restore", 1.0, true,
                "start", PositionType.LOCATION, 0.0, Map.of(), created.plusSeconds(1)));
        clickHouseService.flushEvents();

        clickHouseService.saveExitCandidateAsync(new ExitCandidate(
                "live-before-restore-exit", "live-before-restore-event", "live-before-restore-item", "chute",
                beforeRestoreExit, "live", null, beforeRestoreExit));
        clickHouseService.saveExitCandidateAsync(new ExitCandidate(
                "live-after-restore-exit", "live-after-restore-event", "live-after-restore-item", "chute",
                afterRestoreLiveExit, "live", null, afterRestoreLiveExit));
        clickHouseService.saveExitCandidateAsync(new ExitCandidate(
                "simulation-only-exit", "simulation-only-event", "simulation-only-item", "chute",
                simulationExit, simulationId, restore.plusSeconds(2), restore));
        clickHouseService.flushExitCandidates();
        clickHouseService.projectCompletedJourneys();

        var summary = clickHouseService.getJourneySummary(
                created,
                simulationExit.plusSeconds(1),
                simulationId,
                restore).join();
        assertEquals(2, summary.completedItemCount());
        assertEquals(0, summary.recirculationEventCount());
    }

    @Test
    void simulationCleanupDeletesEveryOperationalAnalyticsTable() {
        String simulationId = "operational-cleanup-simulation";
        Instant timestamp = Instant.parse("2045-01-01T00:00:00Z");
        clickHouseService.saveExitCandidateAsync(new ExitCandidate(
                "cleanup-candidate", "cleanup-event", "cleanup-item", "cleanup-chute",
                timestamp, simulationId, timestamp.minusSeconds(5), timestamp.minusSeconds(10)));
        clickHouseService.saveRecirculationAsync(
                "cleanup-recirculation", timestamp, simulationId, "cleanup-item", List.of("a"), List.of("b"));
        saveSignal(simulationId, "cleanup-signal", timestamp, "CONNECTION_CREATED", true, 1.0);
        clickHouseService.flushExitCandidates();
        clickHouseService.flushRecirculationFacts();
        clickHouseService.flushSimulationConnectionSignals();

        clickHouseService.deleteOperationalAnalyticsForSimulation(simulationId);

        for (String table : List.of(
                "analytics_exit_candidates",
                "analytics_completed_journeys",
                "analytics_recirculation_facts",
                "analytics_simulation_connection_events")) {
            List<Map<String, Object>> rows = clickHouseService.queryForList(
                    "SELECT count() AS count FROM " + table
                            + " WHERE simulation_id = '" + simulationId + "'");
            assertEquals(0L, Long.parseLong(String.valueOf(rows.getFirst().get("count"))));
        }
    }

    @Test
    void operationalEndpointsRejectReversedWindows() {
        Instant from = Instant.parse("2048-01-02T00:00:00Z");
        Instant to = from.minusSeconds(1);
        assertThrows(ResponseStatusException.class, () -> analyticsController.getJourneySummary(from, to));
        assertThrows(ResponseStatusException.class, () -> analyticsController.getConveyorStops(from, to));
    }

    private com.flunav.backend.models.response.JourneySummary summary(Instant from, Instant to) {
        return clickHouseService.getJourneySummary(from, to, null, null).join();
    }

    private void saveSimulation(String simulationId, Instant restore, Instant current) {
        liveSimulationRepository.saveState(new LiveSimulationRepository.SimulationMetadata(
                simulationId, restore, SimulationStatus.READY, current, current, 1.0, 100.0));
    }

    private void saveSignal(
            String simulationId,
            String eventId,
            Instant timestamp,
            String eventType,
            Boolean active,
            Double speed) {
        clickHouseService.saveSimulationConnectionSignalAsync(new ConnectionStateSignal(
                eventId,
                timestamp,
                simulationId,
                "operational-conveyor",
                eventType,
                active,
                speed,
                "operational-source",
                "operational-target"));
    }
}
