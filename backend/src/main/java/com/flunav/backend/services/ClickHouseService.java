package com.flunav.backend.services;

import com.clickhouse.client.api.Client;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flunav.backend.models.analytics.EntityEventType;
import com.flunav.backend.models.analytics.ConnectionStateSignal;
import com.flunav.backend.models.analytics.ExitCandidate;
import com.flunav.backend.models.analytics.LocationTransitMetric;
import com.flunav.backend.models.analytics.MetricEvent;
import com.flunav.backend.models.analytics.AnomalyFinding;
import com.flunav.backend.models.analytics.AnomalyIncident;
import com.flunav.backend.models.analytics.DetectorBaseline;
import com.flunav.backend.models.analytics.LocationFlowObservation;
import com.flunav.backend.models.graph.GraphData;
import com.flunav.backend.models.response.BadActorMetric;
import com.flunav.backend.models.response.EntityEventRecord;
import com.flunav.backend.models.response.JourneySummary;
import com.flunav.backend.models.response.ThroughputMetric;
import com.flunav.backend.models.response.AlarmHistoryRecord;
import com.flunav.backend.models.multisimulation.MultiSimulation;
import com.flunav.backend.models.multisimulation.MultiSimulationReport;
import com.flunav.backend.models.multisimulation.MultiSimulationRun;

import flunav.events.DomainEvent;
import flunav.events.PathTraversedEvent;
import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Public storage facade; owns one schedule per queue and the shared client shutdown. */
@Service
public class ClickHouseService extends ClickHouseAccess {
    private static final Logger logger = LoggerFactory.getLogger(ClickHouseService.class);
    private final ClickHouseEventStorage events;
    private final ClickHouseAnalyticsStorage analytics;
    private final ClickHouseMultiSimulationStorage multiSimulations;

    public ClickHouseService(
            @Value("${clickhouse.url}") String clickhouseUrl,
            @Value("${clickhouse.username}") String username,
            @Value("${clickhouse.password}") String password,
            ObjectMapper objectMapper) {
        super(createClient(clickhouseUrl, username, password), objectMapper, extractDatabase(clickhouseUrl));
        analytics = new ClickHouseAnalyticsStorage(client, objectMapper, clickhouseDatabase);
        events = new ClickHouseEventStorage(client, objectMapper, clickhouseDatabase, analytics);
        multiSimulations = new ClickHouseMultiSimulationStorage(client, objectMapper, clickhouseDatabase);
        try {
            ClickHouseSchemaSetup schema = new ClickHouseSchemaSetup(client, objectMapper, clickhouseDatabase);
            schema.ensureAnalyticsSchema();
            schema.ensureMovementAnalyticsSchema();
            schema.ensureOperationalAnalyticsSchema();
            schema.ensureAlarmAnalyticsSchema();
            schema.ensureAnomalyAnalyticsSchema();
            schema.ensureMultiSimulationSchema();
            logger.info("ClickHouse Client V2 initialized successfully.");
        } catch (Exception e) {
            logger.error("Failed to initialize ClickHouse client", e);
            throw new RuntimeException("ClickHouse init error", e);
        }
    }

    /** The configured URL path selects a database, not the HTTP route used by client-v2. */
    private static Client createClient(String url, String username, String password) {
        try {
            URI configured = URI.create(url);
            URI endpoint = new URI(configured.getScheme(), configured.getUserInfo(), configured.getHost(),
                    configured.getPort(), "/", configured.getQuery(), null);
            return new Client.Builder().addEndpoint(endpoint.toString())
                    .setDefaultDatabase(extractDatabase(url))
                    .setUsername(username).setPassword(password).build();
        } catch (Exception e) {
            logger.error("Failed to initialize ClickHouse client", e);
            throw new RuntimeException("ClickHouse init error", e);
        }
    }

    /** Drains each original queue in its original order before closing the shared client. */
    @PreDestroy
    public void cleanup() {
        try {
            flushAllEventsOrThrow();
        } catch (Exception e) {
            logger.error("Unable to drain all event batches during shutdown", e);
        }
        analytics.drainOnShutdown();
        if (client != null) client.close();
    }


    public synchronized void saveEventAsync(DomainEvent event) {
        events.saveEventAsync(event);
    }

    @Scheduled(fixedRate = 1000)
    public synchronized void flushEvents() {
        events.flushEvents();
    }

    public synchronized void flushAllEventsOrThrow() {
        events.flushAllEventsOrThrow();
    }

    public synchronized void savePathTraversalMetricAsync(PathTraversedEvent event) {
        analytics.savePathTraversalMetricAsync(event);
    }

    public synchronized void flushPathTraversalMetrics() {
        analytics.flushPathTraversalMetrics();
    }

    public synchronized void saveLocationTransitMetricAsync(LocationTransitMetric metric) {
        analytics.saveLocationTransitMetricAsync(metric);
    }

    public synchronized void flushLocationTransitMetrics() {
        analytics.flushLocationTransitMetrics();
    }

    public synchronized void saveExitCandidateAsync(ExitCandidate candidate) {
        analytics.saveExitCandidateAsync(candidate);
    }

    public synchronized void flushExitCandidates() {
        analytics.flushExitCandidates();
    }

    @Scheduled(fixedDelay = 1000)
    public void projectCompletedJourneys() {
        analytics.projectCompletedJourneys();
    }

    public synchronized void saveRecirculationAsync(String id, Instant timestamp, String simulationId, String itemId,
            List<String> previousPath, List<String> newPath) {
        analytics.saveRecirculationAsync(id, timestamp, simulationId, itemId, previousPath, newPath);
    }

    public synchronized void flushRecirculationFacts() {
        analytics.flushRecirculationFacts();
    }

    public synchronized void saveSimulationConnectionSignalAsync(ConnectionStateSignal signal) {
        analytics.saveSimulationConnectionSignalAsync(signal);
    }

    public synchronized void flushSimulationConnectionSignals() {
        analytics.flushSimulationConnectionSignals();
    }

    public void saveSnapshot(String snapshotId, Instant timestamp, GraphData graphData) {
        events.saveSnapshot(snapshotId, timestamp, graphData);
    }

    public synchronized void saveAnomalyFinding(AnomalyFinding finding) {
        analytics.saveAnomalyFinding(finding);
    }

    public synchronized void saveAnomalyIncident(AnomalyIncident incident) {
        analytics.saveAnomalyIncident(incident);
    }

    public synchronized void saveDetectorBaseline(DetectorBaseline baseline) {
        analytics.saveDetectorBaseline(baseline);
    }

    public synchronized void saveComponentFlowObservation(LocationFlowObservation observation, String scopeId,
            String simulationId) {
        analytics.saveComponentFlowObservation(observation, scopeId, simulationId);
    }

    public synchronized void saveComponentFlowBucket(Instant bucketStart, String scopeId, String simulationId,
            String componentId, String componentType, long arrivals, long departures) {
        analytics.saveComponentFlowBucket(bucketStart, scopeId, simulationId, componentId, componentType, arrivals, departures);
    }

    @Scheduled(fixedRate = 1000, scheduler = "clickHouseAnalyticsScheduler")
    public synchronized void flushAnalyticsBatches() {
        analytics.flushAnalyticsBatches();
    }

    public synchronized void flushDetectorBaselines() {
        analytics.flushDetectorBaselines();
    }

    public synchronized void flushComponentFlowBuckets() {
        analytics.flushComponentFlowBuckets();
    }

    public List<TransitSample> getTransitSamples(String scopeId, Instant from, Instant to) {
        return analytics.getTransitSamples(scopeId, from, to);
    }

    public synchronized void saveAlarmFact(DomainEvent event, String simulationId, List<String> affectedItemIds) {
        analytics.saveAlarmFact(event, simulationId, affectedItemIds);
    }

    public synchronized void saveComparison(com.flunav.backend.models.comparison.SimulationComparison comparison) {
        multiSimulations.saveComparison(comparison);
    }

    public java.util.List<com.flunav.backend.models.comparison.SimulationComparison> getComparisons() {
        return multiSimulations.getComparisons();
    }

    public synchronized void saveMultiSimulation(MultiSimulation simulation) {
        multiSimulations.saveMultiSimulation(simulation);
    }

    public Optional<MultiSimulation> getMultiSimulation(String id) {
        return multiSimulations.getMultiSimulation(id);
    }

    public List<MultiSimulation> getMultiSimulations() {
        return multiSimulations.getMultiSimulations();
    }

    public synchronized void saveMultiSimulationRun(MultiSimulationRun run) {
        multiSimulations.saveMultiSimulationRun(run);
    }

    public List<MultiSimulationRun> getMultiSimulationRuns(String id) {
        return multiSimulations.getMultiSimulationRuns(id);
    }

    public synchronized void saveMultiSimulationReport(MultiSimulationReport report) {
        multiSimulations.saveMultiSimulationReport(report);
    }

    public Optional<MultiSimulationReport> getMultiSimulationReport(String id) {
        return multiSimulations.getMultiSimulationReport(id);
    }

    public Optional<Snapshot> getMostRecentSnapshotBefore(Instant timestamp) {
        return events.getMostRecentSnapshotBefore(timestamp);
    }

    public List<DomainEvent> getEventsBetween(Instant startTime, Instant endTime) {
        return events.getEventsBetween(startTime, endTime);
    }

    public List<DomainEvent> getEventsForRecoveryBetween(Instant startTime, Instant endTime) {
        return events.getEventsForRecoveryBetween(startTime, endTime);
    }

    public EventPage getEventsBetweenPage(Instant startTime, Instant endTime, EventPageCursor cursor, int pageSize) {
        return events.getEventsBetweenPage(startTime, endTime, cursor, pageSize);
    }

    public List<DomainEvent> getLatestConfigurationEventsBefore(Instant timestamp) {
        return events.getLatestConfigurationEventsBefore(timestamp);
    }

    public List<Map<String, Object>> queryForList(String sql) {
        return super.queryForList(sql);
    }

    public CompletableFuture<List<ThroughputMetric>> getThroughputHistory(int hours) {
        return analytics.getThroughputHistory(hours);
    }

    public CompletableFuture<List<ThroughputMetric>> getThroughputHistory(Instant from, Instant to, int bucketSeconds) {
        return analytics.getThroughputHistory(from, to, bucketSeconds);
    }

    public void saveThroughputMetric(ThroughputMetric metric) {
        analytics.saveThroughputMetric(metric);
    }

    public CompletableFuture<JourneySummary> getJourneySummary(
            Instant from,
            Instant to,
            String simulationId,
            Instant restoreTimestamp) {
        return analytics.getJourneySummary(from, to, simulationId, restoreTimestamp);
    }

    public List<ConnectionStateSignal> getLiveConnectionSignals(Instant to) {
        return analytics.getLiveConnectionSignals(to);
    }

    public List<ConnectionStateSignal> getSimulationConnectionSignals(String simulationId, Instant to) {
        return analytics.getSimulationConnectionSignals(simulationId, to);
    }

    public void deleteOperationalAnalyticsForSimulation(String simulationId) {
        analytics.deleteOperationalAnalyticsForSimulation(simulationId);
    }

    public CompletableFuture<List<EntityEventRecord>> getEntityEvents(
            EntityEventType entityType,
            String entityId,
            int limit) {
        return analytics.getEntityEvents(entityType, entityId, limit);
    }

    public CompletableFuture<List<AlarmHistoryRecord>> getAlarmHistory(
            String alarmId, String simulationId, Instant from, Instant to) {
        return analytics.getAlarmHistory(alarmId, simulationId, from, to);
    }

    public CompletableFuture<List<String>> getAlarmAffectedItems(String alarmId, String simulationId) {
        return analytics.getAlarmAffectedItems(alarmId, simulationId);
    }

    public CompletableFuture<List<BadActorMetric>> getTopActiveComponents(int limit) {
        return analytics.getTopActiveComponents(limit);
    }

    public synchronized void saveMetricAsync(MetricEvent metric) {
        analytics.saveMetricAsync(metric);
    }

    public synchronized void flushMetrics() {
        analytics.flushMetrics();
    }

    public void saveMetricSnapshots(List<Map<String, Object>> metrics) {
        analytics.saveMetricSnapshots(metrics);
    }

    public void execute(String sql) {
        super.execute(sql);
    }

    public record Snapshot(GraphData graphData, Instant timestamp) {
    }

    public record EventPageCursor(Instant timestampReceived, Instant timestampProcessed, String eventId) {
    }

    public record EventPage(List<DomainEvent> events, EventPageCursor nextCursor, int rowCount) {
        public boolean hasMore(int pageSize) {
            return rowCount == pageSize && nextCursor != null;
        }
    }

    public record TransitSample(String conveyorId, String sourceLocationId, String targetLocationId,
            double durationMillis, Instant timestamp) {
    }
}
