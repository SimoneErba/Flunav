package com.flunav.backend.services;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.query.QueryResponse;
import com.clickhouse.data.ClickHouseFormat;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flunav.backend.context.DatabaseContextHolder;
import com.flunav.backend.models.analytics.EntityEventType;
import com.flunav.backend.models.analytics.ConnectionStateSignal;
import com.flunav.backend.models.analytics.ExitCandidate;
import com.flunav.backend.models.analytics.LocationTransitMetric;
import com.flunav.backend.models.analytics.MetricEvent;
import com.flunav.backend.models.analytics.AnomalyFinding;
import com.flunav.backend.models.analytics.AnomalyIncident;
import com.flunav.backend.models.analytics.DetectorBaseline;
import com.flunav.backend.models.analytics.LocationFlowObservation;
import com.flunav.backend.models.response.BadActorMetric;
import com.flunav.backend.models.response.EntityEventRecord;
import com.flunav.backend.models.response.JourneySummary;
import com.flunav.backend.models.response.ThroughputMetric;
import com.flunav.backend.models.response.AlarmHistoryRecord;
import flunav.types.AlarmSeverity;

import flunav.events.DomainEvent;
import flunav.events.ConnectionActivatedEvent;
import flunav.events.ConnectionCreatedEvent;
import flunav.events.ConnectionDeactivatedEvent;
import flunav.events.ConnectionDeletedEvent;
import flunav.events.ConnectionSpeedChangedEvent;
import flunav.events.PathTraversedEvent;
import flunav.events.AlarmRaisedEvent;
import flunav.events.AlarmClearedEvent;
import flunav.events.ComponentAlarmRaisedEvent;
import flunav.events.ComponentAlarmClearedEvent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.LinkedBlockingQueue;

import com.flunav.backend.services.ClickHouseService.*;
/** Owns analytics queues and queries. Flushing is scheduled exclusively by ClickHouseService. */
final class ClickHouseAnalyticsStorage extends ClickHouseAccess {
    private static final Logger logger = LoggerFactory.getLogger(ClickHouseAnalyticsStorage.class);
    private final LinkedBlockingDeque<PathTraversalMetric> pathTraversalQueue = new LinkedBlockingDeque<>(MAX_QUEUE_SIZE);
    private final LinkedBlockingDeque<LocationTransitMetric> locationTransitQueue = new LinkedBlockingDeque<>(MAX_QUEUE_SIZE);
    private final LinkedBlockingDeque<ExitCandidate> exitCandidateQueue = new LinkedBlockingDeque<>(MAX_QUEUE_SIZE);
    private final LinkedBlockingDeque<RecirculationFact> recirculationQueue = new LinkedBlockingDeque<>(MAX_QUEUE_SIZE);
    private final LinkedBlockingDeque<ConnectionStateSignal> simulationConnectionQueue = new LinkedBlockingDeque<>(MAX_QUEUE_SIZE);
    private final BlockingQueue<MetricEvent> metricQueue = new LinkedBlockingQueue<>(MAX_QUEUE_SIZE);

    ClickHouseAnalyticsStorage(Client client, ObjectMapper mapper, String database) {
        super(client, mapper, database);
    }

    private void drainPathTraversalMetricsOnShutdown() {
        while (!pathTraversalQueue.isEmpty()) {
            int sizeBefore = pathTraversalQueue.size();
            flushPathTraversalMetrics();
            if (pathTraversalQueue.size() >= sizeBefore) {
                break;
            }
        }
    }

    private void drainLocationTransitMetricsOnShutdown() {
        while (!locationTransitQueue.isEmpty()) {
            int sizeBefore = locationTransitQueue.size();
            flushLocationTransitMetrics();
            if (locationTransitQueue.size() >= sizeBefore) {
                break;
            }
        }
    }

    /** Stops shutdown retries when a flush makes no progress; queue durability remains process-local. */
    private void drainQueueOnShutdown(String queueName, BlockingQueue<?> queue, Runnable flushAction) {
        while (!queue.isEmpty()) {
            int sizeBefore = queue.size();
            flushAction.run();
            if (queue.size() >= sizeBefore) {
                logger.error("Unable to drain {} ClickHouse queue during shutdown; {} entries remain",
                        queueName, queue.size());
                break;
            }
        }
    }

    public synchronized void savePathTraversalMetricAsync(PathTraversedEvent event) {
        enqueueOrThrow(pathTraversalQueue, new PathTraversalMetric(
                event.getTimestamp(),
                currentSimulationScope(),
                Objects.toString(event.getEntityId(), ""),
                Objects.toString(event.getPreviousPositionId(), ""),
                event.getPreviousPositionType() != null ? event.getPreviousPositionType().name() : "",
                Objects.toString(event.getNewPositionId(), ""),
                event.getNewPositionType() != null ? event.getNewPositionType().name() : "",
                event.getPath() != null ? event.getPath() : List.of()), "path traversal");

        if (pathTraversalQueue.size() >= BATCH_SIZE) {
            flushPathTraversalMetrics();
        }
    }

    public synchronized void flushPathTraversalMetrics() {
        if (pathTraversalQueue.isEmpty()) {
            return;
        }

        List<PathTraversalMetric> batch = new ArrayList<>();
        pathTraversalQueue.drainTo(batch, BATCH_SIZE);

        if (batch.isEmpty()) {
            return;
        }

        try {
            StringBuilder jsonBatch = new StringBuilder();
            for (PathTraversalMetric metric : batch) {
                Map<String, Object> row = new HashMap<>();
                row.put("event_timestamp", CLICKHOUSE_FORMATTER.format(metric.timestamp()));
                row.put("simulation_id", metric.simulationId());
                row.put("item_id", metric.itemId());
                row.put("previous_position_id", metric.previousPositionId());
                row.put("previous_position_type", metric.previousPositionType());
                row.put("new_position_id", metric.newPositionId());
                row.put("new_position_type", metric.newPositionType());
                row.put("path", metric.path());
                jsonBatch.append(objectMapper.writeValueAsString(row)).append("\n");
            }

            try (var inputStream = new ByteArrayInputStream(jsonBatch.toString().getBytes(StandardCharsets.UTF_8))) {
                client.insert("analytics_path_traversal_ingest", inputStream, ClickHouseFormat.JSONEachRow).get();
            }

            logger.debug("Flushed {} path traversal metrics to ClickHouse", batch.size());
        } catch (Exception e) {
            requeuePathTraversalBatch(batch);
            logger.error("Error flushing path traversal metrics batch to ClickHouse.", e);
        }
    }

    private void requeuePathTraversalBatch(List<PathTraversalMetric> batch) {
        for (int i = batch.size() - 1; i >= 0; i--) {
            pathTraversalQueue.offerFirst(batch.get(i));
        }
    }

    public synchronized void saveLocationTransitMetricAsync(LocationTransitMetric metric) {
        String simulationId = metric.simulationId() != null ? metric.simulationId() : currentSimulationScope();
        enqueueOrThrow(locationTransitQueue, new LocationTransitMetric(
                metric.sourceEventId(),
                metric.timestamp(),
                simulationId,
                metric.itemId(),
                metric.fromLocationId(),
                metric.toLocationId(),
                metric.fromPositionId(),
                metric.fromPositionType(),
                metric.toPositionId(),
                metric.toPositionType(),
                metric.transitTimeMillis(),
                metric.path()), "location transit");

        if (locationTransitQueue.size() >= BATCH_SIZE) {
            flushLocationTransitMetrics();
        }
    }

    public synchronized void flushLocationTransitMetrics() {
        if (locationTransitQueue.isEmpty()) {
            return;
        }

        List<LocationTransitMetric> batch = new ArrayList<>();
        locationTransitQueue.drainTo(batch, BATCH_SIZE);

        if (batch.isEmpty()) {
            return;
        }

        try {
            StringBuilder jsonBatch = new StringBuilder();
            for (LocationTransitMetric metric : batch) {
                Map<String, Object> row = new HashMap<>();
                row.put("event_timestamp", CLICKHOUSE_FORMATTER.format(metric.timestamp()));
                row.put("source_event_id", Objects.toString(metric.sourceEventId(), ""));
                row.put("simulation_id", metric.simulationId());
                row.put("item_id", metric.itemId());
                row.put("from_location_id", metric.fromLocationId());
                row.put("to_location_id", metric.toLocationId());
                row.put("from_position_id", metric.fromPositionId());
                row.put("from_position_type", metric.fromPositionType().name());
                row.put("to_position_id", metric.toPositionId());
                row.put("to_position_type", metric.toPositionType().name());
                row.put("transit_time_ms", metric.transitTimeMillis());
                row.put("path", metric.path());
                jsonBatch.append(objectMapper.writeValueAsString(row)).append("\n");
            }

            try (var inputStream = new ByteArrayInputStream(jsonBatch.toString().getBytes(StandardCharsets.UTF_8))) {
                client.insert("analytics_location_transit_events", inputStream, ClickHouseFormat.JSONEachRow).get();
            }

            logger.debug("Flushed {} location transit metrics to ClickHouse", batch.size());
        } catch (Exception e) {
            requeueLocationTransitBatch(batch);
            logger.error("Error flushing location transit metrics batch to ClickHouse.", e);
        }
    }

    private String currentSimulationScope() {
        String simulationId = DatabaseContextHolder.getSimulationId();
        return simulationId != null ? simulationId : "live";
    }

    private void requeueLocationTransitBatch(List<LocationTransitMetric> batch) {
        for (int i = batch.size() - 1; i >= 0; i--) {
            locationTransitQueue.offerFirst(batch.get(i));
        }
    }

    public synchronized void saveExitCandidateAsync(ExitCandidate candidate) {
        enqueueOrThrow(exitCandidateQueue, candidate, "exit candidate");
        if (exitCandidateQueue.size() >= BATCH_SIZE) {
            flushExitCandidates();
        }
    }

    public synchronized void flushExitCandidates() {
        List<ExitCandidate> batch = new ArrayList<>();
        exitCandidateQueue.drainTo(batch, BATCH_SIZE);
        if (batch.isEmpty()) {
            return;
        }

        try {
            StringBuilder jsonBatch = new StringBuilder();
            for (ExitCandidate candidate : batch) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("candidate_id", candidate.candidateId());
                row.put("exit_event_id", candidate.exitEventId());
                row.put("item_id", candidate.itemId());
                row.put("chute_id", candidate.chuteId());
                row.put("exit_timestamp", CLICKHOUSE_FORMATTER.format(candidate.exitTimestamp()));
                row.put("simulation_id", candidate.simulationId());
                row.put("simulation_created_at", candidate.simulationCreatedAt() == null
                        ? null
                        : CLICKHOUSE_FORMATTER.format(candidate.simulationCreatedAt()));
                row.put("live_history_cutoff", candidate.liveHistoryCutoff() == null
                        ? null
                        : CLICKHOUSE_FORMATTER.format(candidate.liveHistoryCutoff()));
                jsonBatch.append(objectMapper.writeValueAsString(row)).append('\n');
            }
            try (var inputStream = new ByteArrayInputStream(jsonBatch.toString().getBytes(StandardCharsets.UTF_8))) {
                client.insert("analytics_exit_candidates", inputStream, ClickHouseFormat.JSONEachRow).get();
            }
        } catch (Exception e) {
            for (int index = batch.size() - 1; index >= 0; index--) {
                exitCandidateQueue.offerFirst(batch.get(index));
            }
            logger.error("Failed to flush completed-journey exit candidates", e);
        }
    }

    public void projectCompletedJourneys() {
        List<ExitCandidate> candidates;
        try {
            flushExitCandidates();
            candidates = getUnresolvedExitCandidates(500);
        } catch (Exception e) {
            logger.warn("Completed-journey projection will retry after ClickHouse becomes available: {}",
                    e.getMessage());
            return;
        }
        for (ExitCandidate candidate : candidates) {
            try {
                Instant createdAt = candidate.simulationCreatedAt() != null
                        ? candidate.simulationCreatedAt()
                        : findLatestItemCreation(
                                candidate.itemId(),
                                candidate.liveHistoryCutoff() != null
                                        ? candidate.liveHistoryCutoff()
                                        : candidate.exitTimestamp())
                                .orElse(null);
                if (createdAt == null) {
                    continue;
                }
                long traversalMillis = java.time.Duration.between(createdAt, candidate.exitTimestamp()).toMillis();
                if (traversalMillis < 0) {
                    logger.warn("Skipping exit candidate {} because creation follows exit", candidate.candidateId());
                    continue;
                }
                insertCompletedJourney(candidate, createdAt, traversalMillis);
            } catch (Exception e) {
                logger.warn("Completed-journey projection will retry candidate {}: {}",
                        candidate.candidateId(), e.getMessage());
            }
        }
    }

    private List<ExitCandidate> getUnresolvedExitCandidates(int limit) {
        String query = """
                SELECT candidate_id, exit_event_id, item_id, chute_id, exit_timestamp,
                       simulation_id, simulation_created_at, live_history_cutoff
                FROM analytics_exit_candidates AS candidates FINAL
                LEFT ANTI JOIN analytics_completed_journeys AS journeys FINAL
                  ON candidates.simulation_id = journeys.simulation_id
                 AND candidates.candidate_id = journeys.journey_id
                ORDER BY exit_timestamp
                LIMIT {limit:UInt32}
                FORMAT JSONEachRow
                SETTINGS date_time_output_format = 'iso'
                """;
        List<ExitCandidate> candidates = new ArrayList<>();
        try (QueryResponse response = client.query(query, Map.of("limit", Math.max(1, limit))).get();
                InputStream inputStream = response.getInputStream()) {
            MappingIterator<Map<String, Object>> rows = objectMapper.readerFor(Map.class).readValues(inputStream);
            while (rows.hasNext()) {
                Map<String, Object> row = rows.next();
                candidates.add(new ExitCandidate(
                        Objects.toString(row.get("candidate_id"), ""),
                        Objects.toString(row.get("exit_event_id"), ""),
                        Objects.toString(row.get("item_id"), ""),
                        Objects.toString(row.get("chute_id"), ""),
                        parseClickHouseInstant(row.get("exit_timestamp")),
                        Objects.toString(row.get("simulation_id"), "live"),
                        parseNullableInstant(row.get("simulation_created_at")),
                        parseNullableInstant(row.get("live_history_cutoff"))));
            }
            return candidates;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load unresolved exit candidates", e);
        }
    }

    private Optional<Instant> findLatestItemCreation(String itemId, Instant cutoff) {
        String query = """
                SELECT maxOrNull(timestamp_received) AS created_timestamp
                FROM Events
                WHERE event_type = 'ITEM_CREATED'
                  AND entity_id = {item_id:String}
                  AND timestamp_received <= {cutoff:DateTime64(3)}
                FORMAT JSONEachRow
                SETTINGS date_time_output_format = 'iso'
                """;
        try (QueryResponse response = client.query(query, Map.of(
                "item_id", itemId,
                "cutoff", CLICKHOUSE_FORMATTER.format(cutoff))).get();
                InputStream inputStream = response.getInputStream()) {
            MappingIterator<Map<String, Object>> rows = objectMapper.readerFor(Map.class).readValues(inputStream);
            if (!rows.hasNext()) {
                return Optional.empty();
            }
            return Optional.ofNullable(parseNullableInstant(rows.next().get("created_timestamp")));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to resolve item creation", e);
        }
    }

    private void insertCompletedJourney(ExitCandidate candidate, Instant createdAt, long traversalMillis)
            throws Exception {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("journey_id", candidate.candidateId());
        row.put("item_id", candidate.itemId());
        row.put("chute_id", candidate.chuteId());
        row.put("created_timestamp", CLICKHOUSE_FORMATTER.format(createdAt));
        row.put("exit_timestamp", CLICKHOUSE_FORMATTER.format(candidate.exitTimestamp()));
        row.put("traversal_time_ms", traversalMillis);
        row.put("simulation_id", candidate.simulationId());
        try (var inputStream = new ByteArrayInputStream(
                objectMapper.writeValueAsBytes(row))) {
            client.insert("analytics_completed_journeys", inputStream, ClickHouseFormat.JSONEachRow).get();
        }
    }

    public synchronized void saveRecirculationAsync(String id, Instant timestamp, String simulationId, String itemId,
            List<String> previousPath, List<String> newPath) {
        enqueueOrThrow(recirculationQueue, new RecirculationFact(
                id, timestamp, simulationId, itemId, List.copyOf(previousPath), List.copyOf(newPath)),
                "recirculation");
        if (recirculationQueue.size() >= BATCH_SIZE) {
            flushRecirculationFacts();
        }
    }

    public synchronized void flushRecirculationFacts() {
        List<RecirculationFact> batch = new ArrayList<>();
        recirculationQueue.drainTo(batch, BATCH_SIZE);
        if (batch.isEmpty()) {
            return;
        }
        try {
            StringBuilder jsonBatch = new StringBuilder();
            for (RecirculationFact fact : batch) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("recirculation_id", fact.id());
                row.put("event_timestamp", CLICKHOUSE_FORMATTER.format(fact.timestamp()));
                row.put("simulation_id", fact.simulationId());
                row.put("item_id", fact.itemId());
                row.put("previous_path", fact.previousPath());
                row.put("new_path", fact.newPath());
                jsonBatch.append(objectMapper.writeValueAsString(row)).append('\n');
            }
            try (var inputStream = new ByteArrayInputStream(jsonBatch.toString().getBytes(StandardCharsets.UTF_8))) {
                client.insert("analytics_recirculation_facts", inputStream, ClickHouseFormat.JSONEachRow).get();
            }
        } catch (Exception e) {
            for (int index = batch.size() - 1; index >= 0; index--) {
                recirculationQueue.offerFirst(batch.get(index));
            }
            logger.error("Failed to flush recirculation facts", e);
        }
    }

    public synchronized void saveSimulationConnectionSignalAsync(ConnectionStateSignal signal) {
        enqueueOrThrow(simulationConnectionQueue, signal, "simulation connection");
        if (simulationConnectionQueue.size() >= BATCH_SIZE) {
            flushSimulationConnectionSignals();
        }
    }

    public synchronized void flushSimulationConnectionSignals() {
        List<ConnectionStateSignal> batch = new ArrayList<>();
        simulationConnectionQueue.drainTo(batch, BATCH_SIZE);
        if (batch.isEmpty()) {
            return;
        }
        try {
            StringBuilder jsonBatch = new StringBuilder();
            for (ConnectionStateSignal signal : batch) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("event_id", signal.eventId());
                row.put("event_timestamp", CLICKHOUSE_FORMATTER.format(signal.timestamp()));
                row.put("simulation_id", signal.simulationId());
                row.put("conveyor_id", signal.conveyorId());
                row.put("event_type", signal.eventType());
                row.put("active", signal.active());
                row.put("speed", signal.speed());
                row.put("source_id", Objects.toString(signal.sourceId(), ""));
                row.put("target_id", Objects.toString(signal.targetId(), ""));
                jsonBatch.append(objectMapper.writeValueAsString(row)).append('\n');
            }
            try (var inputStream = new ByteArrayInputStream(jsonBatch.toString().getBytes(StandardCharsets.UTF_8))) {
                client.insert("analytics_simulation_connection_events", inputStream, ClickHouseFormat.JSONEachRow).get();
            }
        } catch (Exception e) {
            for (int index = batch.size() - 1; index >= 0; index--) {
                simulationConnectionQueue.offerFirst(batch.get(index));
            }
            logger.error("Failed to flush simulation conveyor-state facts", e);
        }
    }

    public void saveAnomalyFinding(AnomalyFinding finding) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("finding_id", finding.findingId());
        row.put("scope_id", finding.scopeId());
        row.put("simulation_id", Objects.toString(finding.simulationId(), ""));
        row.put("detector", finding.detector().name());
        row.put("detector_version", finding.detectorVersion());
        row.put("temporal_mode", finding.temporalMode().name());
        row.put("component_id", Objects.toString(finding.componentId(), ""));
        row.put("component_type", finding.componentType().name());
        row.put("item_id", Objects.toString(finding.itemId(), ""));
        row.put("previous_position_id", Objects.toString(finding.previousPositionId(), ""));
        row.put("reported_position_id", Objects.toString(finding.reportedPositionId(), ""));
        row.put("expected_intermediate_positions", finding.expectedIntermediatePositions());
        row.put("observed_metrics_json", writeJson(finding.observedMetrics()));
        row.put("baseline_mean", finding.baselineMean());
        row.put("baseline_median", finding.baselineMedian());
        row.put("baseline_stddev", finding.baselineStddev());
        row.put("baseline_mad", finding.baselineMad());
        row.put("z_score", finding.zScore());
        row.put("modified_z_score", finding.modifiedZScore());
        row.put("sample_count", finding.sampleCount());
        row.put("baseline_window_start", formatNullable(finding.baselineWindowStart()));
        row.put("baseline_window_end", formatNullable(finding.baselineWindowEnd()));
        row.put("severity", finding.severity().name());
        row.put("alarm_id", Objects.toString(finding.alarmId(), ""));
        row.put("alarm_state", finding.alarmState().name());
        row.put("observation_timestamp", CLICKHOUSE_FORMATTER.format(finding.observationTimestamp()));
        row.put("tick_timestamp", CLICKHOUSE_FORMATTER.format(finding.tickTimestamp()));
        insertJsonRows("analytics_anomaly_findings", List.of(row));
    }

    public void saveAnomalyIncident(AnomalyIncident incident) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("incident_id", incident.incidentId());
        row.put("scope_id", incident.scopeId());
        row.put("simulation_id", Objects.toString(incident.simulationId(), ""));
        row.put("probable_root_component_id", incident.probableRootComponentId());
        row.put("confidence", incident.confidence());
        row.put("finding_ids", incident.findingIds());
        row.put("component_ids", incident.componentIds());
        row.put("first_finding_timestamp", CLICKHOUSE_FORMATTER.format(incident.firstFindingTimestamp()));
        row.put("updated_at", CLICKHOUSE_FORMATTER.format(incident.updatedAt()));
        insertJsonRows("analytics_anomaly_incidents", List.of(row));
    }

    public void saveDetectorBaseline(DetectorBaseline baseline) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("baseline_id", baseline.baselineId());
        row.put("scope_id", baseline.scopeId());
        row.put("detector_version", baseline.detectorVersion());
        row.put("detector", baseline.detector().name());
        row.put("component_id", Objects.toString(baseline.componentId(), ""));
        row.put("path_key", baseline.pathKey());
        row.put("window_start", CLICKHOUSE_FORMATTER.format(baseline.windowStart()));
        row.put("window_end", CLICKHOUSE_FORMATTER.format(baseline.windowEnd()));
        row.put("sample_count", baseline.sampleCount());
        row.put("mean", baseline.mean());
        row.put("population_stddev", baseline.populationStddev());
        row.put("median", baseline.median());
        row.put("mad", baseline.mad());
        row.put("minimum", baseline.minimum());
        row.put("maximum", baseline.maximum());
        row.put("calculated_at", CLICKHOUSE_FORMATTER.format(baseline.calculatedAt()));
        row.put("epoch_started_at", CLICKHOUSE_FORMATTER.format(baseline.epochStartedAt()));
        insertJsonRows("analytics_detector_baselines", List.of(row));
    }

    public void saveComponentFlowObservation(LocationFlowObservation observation, String scopeId,
            String simulationId) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("source_event_id", observation.sourceEventId());
        row.put("event_timestamp", CLICKHOUSE_FORMATTER.format(observation.timestamp()));
        row.put("scope_id", scopeId);
        row.put("simulation_id", Objects.toString(simulationId, ""));
        row.put("component_id", observation.locationId());
        row.put("component_type", "LOCATION");
        row.put("direction", observation.direction().name());
        row.put("item_id", observation.itemId());
        insertJsonRows("analytics_component_flow_events", List.of(row));
    }

    public void saveComponentFlowBucket(Instant bucketStart, String scopeId, String simulationId,
            String componentId, String componentType, long arrivals, long departures) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("bucket_start", CLICKHOUSE_FORMATTER.format(bucketStart));
        row.put("scope_id", scopeId);
        row.put("simulation_id", Objects.toString(simulationId, ""));
        row.put("component_id", componentId);
        row.put("component_type", componentType);
        row.put("arrivals", arrivals);
        row.put("departures", departures);
        row.put("throughput", departures);
        row.put("pressure", arrivals - departures);
        insertJsonRows("analytics_component_flow_1m", List.of(row));
    }

    public List<TransitSample> getTransitSamples(String scopeId, Instant from, Instant to) {
        String sourceScope = scopeId.equals("live") ? "live" : scopeId;
        String sql = """
                SELECT from_position_id AS conveyor_id, from_location_id, to_location_id,
                       toFloat64(transit_time_ms) AS duration,
                       toUnixTimestamp64Milli(event_timestamp) AS event_ms
                FROM analytics_location_transit_events
                WHERE simulation_id = {scope:String}
                  AND event_timestamp >= {from:DateTime64(3, 'UTC')}
                  AND event_timestamp < {to:DateTime64(3, 'UTC')}
                FORMAT JSONEachRow
                """;
        List<TransitSample> samples = new ArrayList<>();
        try (QueryResponse response = client.query(sql, Map.of(
                "scope", sourceScope,
                "from", CLICKHOUSE_FORMATTER.format(from),
                "to", CLICKHOUSE_FORMATTER.format(to))).get();
                InputStream input = response.getInputStream()) {
            MappingIterator<Map<String, Object>> rows = objectMapper.readerFor(Map.class).readValues(input);
            while (rows.hasNext()) {
                Map<String, Object> row = rows.next();
                samples.add(new TransitSample(Objects.toString(row.get("conveyor_id"), ""),
                        Objects.toString(row.get("from_location_id"), ""),
                        Objects.toString(row.get("to_location_id"), ""),
                        asDouble(row.get("duration")),
                        Instant.ofEpochMilli(Long.parseLong(String.valueOf(row.get("event_ms"))))));
            }
            return samples;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load transit baseline samples", e);
        }
    }

    /** Stores alarm analytics after reduction, with the current simulation namespace for isolated cleanup. */
    public synchronized void saveAlarmFact(DomainEvent event, String simulationId, List<String> affectedItemIds) {
        if (!(event instanceof AlarmRaisedEvent) && !(event instanceof AlarmClearedEvent)
                && !(event instanceof ComponentAlarmRaisedEvent) && !(event instanceof ComponentAlarmClearedEvent)) {
            return;
        }
        String alarmId;
        String conveyorId;
        String findingId = "";
        String componentType = "CONVEYOR";
        String source = "MANUAL";
        String severity;
        String typology;
        boolean stopsConveyor;
        if (event instanceof AlarmRaisedEvent raised) {
            alarmId = raised.getAlarmId();
            conveyorId = raised.getConveyorId();
            severity = raised.getSeverity().name();
            typology = raised.getTypology();
            stopsConveyor = raised.isStopsConveyor();
        } else if (event instanceof AlarmClearedEvent cleared) {
            alarmId = cleared.getAlarmId();
            conveyorId = cleared.getConveyorId();
            severity = cleared.getSeverity().name();
            typology = cleared.getTypology();
            stopsConveyor = cleared.isStopsConveyor();
        } else if (event instanceof ComponentAlarmRaisedEvent raised) {
            alarmId = raised.getAlarmId();
            conveyorId = raised.getComponentId();
            findingId = Objects.toString(raised.getFindingId(), "");
            componentType = raised.getComponentType().name();
            source = raised.getSource().name();
            severity = raised.getSeverity().name();
            typology = raised.getTypology();
            stopsConveyor = raised.isStopsComponent();
        } else {
            ComponentAlarmClearedEvent cleared = (ComponentAlarmClearedEvent) event;
            alarmId = cleared.getAlarmId();
            conveyorId = cleared.getComponentId();
            componentType = cleared.getComponentType().name();
            severity = "WARNING";
            typology = "COMPONENT_ALARM";
            stopsConveyor = false;
        }

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("event_id", event.getEventId());
        row.put("alarm_id", alarmId);
        row.put("finding_id", findingId);
        row.put("conveyor_id", conveyorId);
        row.put("component_type", componentType);
        row.put("event_type", event.getEventType());
        row.put("severity", severity);
        row.put("typology", typology);
        row.put("source", source);
        row.put("stops_conveyor", stopsConveyor ? 1 : 0);
        row.put("event_timestamp", CLICKHOUSE_FORMATTER.format(event.getTimestamp()));
        row.put("simulation_id", simulationId != null ? simulationId : "");
        insertJsonRows("analytics_alarm_events", List.of(row));

        if ((event instanceof AlarmRaisedEvent || event instanceof ComponentAlarmRaisedEvent)
                && stopsConveyor && affectedItemIds != null
                && !affectedItemIds.isEmpty()) {
            List<Map<String, Object>> itemRows = affectedItemIds.stream().distinct().map(itemId -> {
                Map<String, Object> itemRow = new LinkedHashMap<>();
                itemRow.put("event_id", event.getEventId());
                itemRow.put("alarm_id", alarmId);
                itemRow.put("conveyor_id", conveyorId);
                itemRow.put("item_id", itemId);
                itemRow.put("captured_at", CLICKHOUSE_FORMATTER.format(event.getTimestamp()));
                itemRow.put("simulation_id", simulationId != null ? simulationId : "");
                return itemRow;
            }).toList();
            insertJsonRows("analytics_alarm_affected_items", itemRows);
        }
    }

    public CompletableFuture<List<ThroughputMetric>> getThroughputHistory(int hours) {
        Instant to = Instant.now();
        Instant from = to.minusSeconds(Math.max(1, hours) * 3600L);
        return getThroughputHistory(from, to, 5);
    }

    public CompletableFuture<List<ThroughputMetric>> getThroughputHistory(Instant from, Instant to, int bucketSeconds) {
        int normalizedBucketSeconds = Math.max(1, bucketSeconds);
        Instant end = to != null ? to : Instant.now();
        Instant start = from != null ? from : end.minusSeconds(24 * 3600L);

        String sql = """
                    SELECT
                        toStartOfInterval(bucket_start, INTERVAL %d SECOND) as ts,
                        sum(items_entered) as entered,
                        sum(items_exited) as exited,
                        argMax(items_current, bucket_start) as current
                    FROM %s.analytics_time_series
                    WHERE bucket_start >= {from:DateTime64(3)}
                      AND bucket_start <= {to:DateTime64(3)}
                    GROUP BY ts
                    ORDER BY ts ASC
                    FORMAT JSONEachRow
                    SETTINGS
                        date_time_output_format = 'iso',
                        output_format_json_quote_64bit_integers = 0
                """.formatted(normalizedBucketSeconds, clickhouseDatabase);

        return CompletableFuture.supplyAsync(() -> {
            List<ThroughputMetric> metrics = new ArrayList<>();
            try (QueryResponse response = client.query(sql, Map.of(
                    "from", CLICKHOUSE_FORMATTER.format(start),
                    "to", CLICKHOUSE_FORMATTER.format(end))).get()) {
                try (InputStream inputStream = response.getInputStream()) {
                    MappingIterator<Map<String, Object>> it = objectMapper
                            .readerFor(Map.class)
                            .readValues(inputStream);

                    while (it.hasNext()) {
                        Map<String, Object> row = it.next();
                        metrics.add(new ThroughputMetric(
                                parseClickHouseInstant(row.get("ts")),
                                asLong(row.get("entered")),
                                asLong(row.get("exited")),
                                asLong(row.get("current")),
                                normalizedBucketSeconds));
                    }
                }
            } catch (Exception e) {
                logger.error("Failed to fetch throughput history", e);
                throw new CompletionException("Failed to fetch throughput history", e);
            }
            return metrics;
        });
    }

    public void saveThroughputMetric(ThroughputMetric metric) {
        try {
            Map<String, Object> row = new HashMap<>();
            row.put("bucket_start", CLICKHOUSE_FORMATTER.format(metric.getTimestamp()));
            row.put("bucket_seconds", metric.getBucketSeconds());
            row.put("items_entered", metric.getItemsEntered());
            row.put("items_exited", metric.getItemsExited());
            row.put("items_current", metric.getItemsCurrent());

            try (var inputStream = new ByteArrayInputStream(
                    (objectMapper.writeValueAsString(row) + "\n").getBytes(StandardCharsets.UTF_8))) {
                client.insert("analytics_time_series", inputStream, ClickHouseFormat.JSONEachRow).get();
            }
        } catch (Exception e) {
            logger.error("Failed to save throughput metric for bucket {}", metric.getTimestamp(), e);
            throw new RuntimeException("Failed to save throughput metric", e);
        }
    }

    public CompletableFuture<JourneySummary> getJourneySummary(
            Instant from,
            Instant to,
            String simulationId,
            Instant restoreTimestamp) {
        boolean simulation = simulationId != null;
        String scopeFilter = simulation
                ? "((simulation_id = 'live' AND exit_timestamp <= {restore:DateTime64(3)}) "
                        + "OR (simulation_id = {simulation_id:String} AND exit_timestamp > {restore:DateTime64(3)}))"
                : "simulation_id = 'live'";
        String recirculationScopeFilter = simulation
                ? "((j.simulation_id = 'live' AND r.simulation_id = 'live') "
                        + "OR (j.simulation_id = {simulation_id:String} AND "
                        + "((r.simulation_id = 'live' AND r.event_timestamp <= {restore:DateTime64(3)}) "
                        + "OR (r.simulation_id = {simulation_id:String} "
                        + "AND r.event_timestamp > {restore:DateTime64(3)}))))"
                : "r.simulation_id = 'live'";
        String sql = """
                WITH selected_journeys AS
                (
                    SELECT journey_id, item_id, created_timestamp, exit_timestamp, traversal_time_ms, simulation_id
                    FROM analytics_completed_journeys FINAL
                    WHERE exit_timestamp >= {from:DateTime64(3)}
                      AND exit_timestamp <= {to:DateTime64(3)}
                      AND %s
                ),
                selected_recirculations AS
                (
                    SELECT r.recirculation_id, j.journey_id
                    FROM analytics_recirculation_facts AS r FINAL
                    INNER JOIN selected_journeys AS j
                      ON r.item_id = j.item_id
                     AND r.event_timestamp >= j.created_timestamp
                     AND r.event_timestamp <= j.exit_timestamp
                     AND %s
                )
                SELECT
                    count() AS completed_count,
                    if(count() = 0, 0, avg(traversal_time_ms)) AS average_ms,
                    if(count() = 0, 0, min(traversal_time_ms)) AS minimum_ms,
                    if(count() = 0, 0, max(traversal_time_ms)) AS maximum_ms,
                    if(count() = 0, 0, quantileExact(0.50)(traversal_time_ms)) AS p50_ms,
                    if(count() = 0, 0, quantileExact(0.90)(traversal_time_ms)) AS p90_ms,
                    if(count() = 0, 0, quantileExact(0.95)(traversal_time_ms)) AS p95_ms,
                    if(count() = 0, 0, quantileExact(0.99)(traversal_time_ms)) AS p99_ms,
                    (SELECT count() FROM selected_recirculations) AS recirculation_count,
                    (SELECT uniqExact(journey_id) FROM selected_recirculations) AS journeys_with_recirculation
                FROM selected_journeys
                FORMAT JSONEachRow
                SETTINGS output_format_json_quote_64bit_integers = 0
                """.formatted(scopeFilter, recirculationScopeFilter);

        Map<String, Object> parameters = new HashMap<>();
        parameters.put("from", CLICKHOUSE_FORMATTER.format(from));
        parameters.put("to", CLICKHOUSE_FORMATTER.format(to));
        if (simulation) {
            parameters.put("simulation_id", simulationId);
            parameters.put("restore", CLICKHOUSE_FORMATTER.format(restoreTimestamp));
        }

        return CompletableFuture.supplyAsync(() -> {
            try (QueryResponse response = client.query(sql, parameters).get();
                    InputStream inputStream = response.getInputStream()) {
                MappingIterator<Map<String, Object>> rows = objectMapper.readerFor(Map.class).readValues(inputStream);
                if (!rows.hasNext()) {
                    return JourneySummary.empty();
                }
                Map<String, Object> row = rows.next();
                long completed = asLong(row.get("completed_count"));
                long recirculatedJourneys = asLong(row.get("journeys_with_recirculation"));
                return new JourneySummary(
                        completed,
                        asDouble(row.get("average_ms")),
                        asLong(row.get("minimum_ms")),
                        asLong(row.get("maximum_ms")),
                        asLong(row.get("p50_ms")),
                        asLong(row.get("p90_ms")),
                        asLong(row.get("p95_ms")),
                        asLong(row.get("p99_ms")),
                        asLong(row.get("recirculation_count")),
                        recirculatedJourneys,
                        completed == 0 ? 0.0 : recirculatedJourneys * 100.0 / completed);
            } catch (Exception e) {
                throw new CompletionException("Failed to fetch journey analytics", e);
            }
        });
    }

    public List<ConnectionStateSignal> getLiveConnectionSignals(Instant to) {
        String sql = """
                SELECT data
                FROM Events
                WHERE event_type IN (
                    'CONNECTION_CREATED', 'CONNECTION_ACTIVATED', 'CONNECTION_DEACTIVATED',
                    'CONNECTION_SPEED_CHANGED', 'CONNECTION_DELETED')
                  AND timestamp_received <= {to:DateTime64(3)}
                ORDER BY timestamp_received, timestamp_processed, event_id
                FORMAT JSONEachRow
                """;
        List<ConnectionStateSignal> signals = new ArrayList<>();
        try (QueryResponse response = client.query(sql, Map.of("to", CLICKHOUSE_FORMATTER.format(to))).get();
                InputStream inputStream = response.getInputStream()) {
            MappingIterator<Map<String, Object>> rows = objectMapper.readerFor(Map.class).readValues(inputStream);
            while (rows.hasNext()) {
                DomainEvent event = objectMapper.convertValue(rows.next().get("data"), DomainEvent.class);
                ConnectionStateSignal signal = toConnectionStateSignal(event, "live");
                if (signal != null) {
                    signals.add(signal);
                }
            }
            return signals;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load live conveyor state events", e);
        }
    }

    public List<ConnectionStateSignal> getSimulationConnectionSignals(String simulationId, Instant to) {
        flushSimulationConnectionSignals();
        String sql = """
                SELECT event_id, event_timestamp, simulation_id, conveyor_id, event_type,
                       active, speed, source_id, target_id
                FROM analytics_simulation_connection_events FINAL
                WHERE simulation_id = {simulation_id:String}
                  AND event_timestamp <= {to:DateTime64(3)}
                ORDER BY event_timestamp, event_id
                FORMAT JSONEachRow
                SETTINGS date_time_output_format = 'iso'
                """;
        List<ConnectionStateSignal> signals = new ArrayList<>();
        try (QueryResponse response = client.query(sql, Map.of(
                "simulation_id", simulationId,
                "to", CLICKHOUSE_FORMATTER.format(to))).get();
                InputStream inputStream = response.getInputStream()) {
            MappingIterator<Map<String, Object>> rows = objectMapper.readerFor(Map.class).readValues(inputStream);
            while (rows.hasNext()) {
                Map<String, Object> row = rows.next();
                signals.add(new ConnectionStateSignal(
                        Objects.toString(row.get("event_id"), ""),
                        parseClickHouseInstant(row.get("event_timestamp")),
                        Objects.toString(row.get("simulation_id"), simulationId),
                        Objects.toString(row.get("conveyor_id"), ""),
                        Objects.toString(row.get("event_type"), ""),
                        asNullableBoolean(row.get("active")),
                        asNullableDouble(row.get("speed")),
                        Objects.toString(row.get("source_id"), ""),
                        Objects.toString(row.get("target_id"), "")));
            }
            return signals;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load simulation conveyor state events", e);
        }
    }

    private ConnectionStateSignal toConnectionStateSignal(DomainEvent event, String simulationId) {
        return switch (event) {
            case ConnectionCreatedEvent created -> new ConnectionStateSignal(
                    created.getEventId(), created.getTimestamp(), simulationId, created.getConnectionId(),
                    created.getEventType(), created.getIsActive(), created.getSpeed(),
                    created.getSourceId(), created.getTargetId());
            case ConnectionActivatedEvent activated -> new ConnectionStateSignal(
                    activated.getEventId(), activated.getTimestamp(), simulationId, activated.getEntityId(),
                    activated.getEventType(), true, null, "", "");
            case ConnectionDeactivatedEvent deactivated -> new ConnectionStateSignal(
                    deactivated.getEventId(), deactivated.getTimestamp(), simulationId, deactivated.getEntityId(),
                    deactivated.getEventType(), false, null, "", "");
            case ConnectionSpeedChangedEvent speedChanged -> new ConnectionStateSignal(
                    speedChanged.getEventId(), speedChanged.getTimestamp(), simulationId, speedChanged.getEntityId(),
                    speedChanged.getEventType(), null, speedChanged.getSpeed(), "", "");
            case ConnectionDeletedEvent deleted -> new ConnectionStateSignal(
                    deleted.getEventId(), deleted.getTimestamp(), simulationId, "", deleted.getEventType(),
                    null, null, deleted.getSourceLocationId(), deleted.getTargetLocationId());
            default -> null;
        };
    }

    public void deleteOperationalAnalyticsForSimulation(String simulationId) {
        locationTransitQueue.removeIf(metric -> simulationId.equals(metric.simulationId()));
        pathTraversalQueue.removeIf(metric -> simulationId.equals(metric.simulationId()));
        exitCandidateQueue.removeIf(candidate -> simulationId.equals(candidate.simulationId()));
        recirculationQueue.removeIf(fact -> simulationId.equals(fact.simulationId()));
        simulationConnectionQueue.removeIf(signal -> simulationId.equals(signal.simulationId()));
        IllegalStateException cleanupFailure = null;
        for (String table : List.of(
                "analytics_exit_candidates",
                "analytics_completed_journeys",
                "analytics_recirculation_facts",
                "analytics_simulation_connection_events",
                "analytics_alarm_events",
                "analytics_alarm_affected_items",
                "analytics_anomaly_findings",
                "analytics_anomaly_incidents",
                "analytics_detector_baselines",
                "analytics_component_flow_events",
                "analytics_component_flow_1m",
                "analytics_location_transit_events",
                "analytics_location_transit_counts",
                "analytics_path_transit_stats")) {
            String scopeColumn = "analytics_detector_baselines".equals(table) ? "scope_id" : "simulation_id";
            String sql = "ALTER TABLE " + table
                    + " DELETE WHERE " + scopeColumn + " = {simulation_id:String} SETTINGS mutations_sync = 1";
            try (QueryResponse ignored = client.query(sql, Map.of("simulation_id", simulationId)).get()) {
                // Mutation completion is enforced by mutations_sync.
            } catch (Exception e) {
                logger.error("Failed to delete simulation analytics from {}", table, e);
                if (cleanupFailure == null) {
                    cleanupFailure = new IllegalStateException(
                            "Failed to delete one or more simulation analytics tables", e);
                } else {
                    cleanupFailure.addSuppressed(e);
                }
            }
        }
        if (cleanupFailure != null) {
            throw cleanupFailure;
        }
    }

    public CompletableFuture<List<EntityEventRecord>> getEntityEvents(
            EntityEventType entityType,
            String entityId,
            int limit) {
        String eventTypeFilter = switch (entityType) {
            case ITEM -> "(startsWith(event_type, 'ITEM_') OR event_type = 'PATH_TRAVERSED')";
            case LOCATION -> "(startsWith(event_type, 'LOCATION_') OR event_type = 'CHUTE_EMPTY')";
            case CONVEYOR -> "startsWith(event_type, 'CONNECTION_')";
        };

        String sql = """
                SELECT
                    event_id,
                    event_type,
                    entity_id,
                    timestamp_received,
                    timestamp_processed,
                    data
                FROM (
                    SELECT
                        event_id,
                        event_type,
                        entity_id,
                        timestamp_received,
                        timestamp_processed,
                        data
                    FROM Events
                    WHERE entity_id = {entityId:String}
                      AND %s
                    ORDER BY timestamp_received DESC, timestamp_processed DESC, event_id DESC
                    LIMIT {limit:UInt32}
                )
                ORDER BY timestamp_received ASC, timestamp_processed ASC, event_id ASC
                FORMAT JSONEachRow
                SETTINGS
                    date_time_output_format = 'iso',
                    output_format_json_quote_64bit_integers = 0
                """.formatted(eventTypeFilter);

        return CompletableFuture.supplyAsync(() -> {
            List<EntityEventRecord> events = new ArrayList<>();
            try (QueryResponse response = client.query(sql, Map.of("entityId", entityId, "limit", limit)).get();
                    InputStream inputStream = response.getInputStream()) {
                var mapType = objectMapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class);
                MappingIterator<Map<String, Object>> iterator = objectMapper.readerFor(mapType).readValues(inputStream);

                while (iterator.hasNext()) {
                    Map<String, Object> row = iterator.next();
                    events.add(new EntityEventRecord(
                            String.valueOf(row.get("event_id")),
                            String.valueOf(row.get("event_type")),
                            String.valueOf(row.get("entity_id")),
                            parseClickHouseInstant(row.get("timestamp_received")),
                            parseClickHouseInstant(row.get("timestamp_processed")),
                            asPayload(row.get("data"))));
                }

                return events;
            } catch (Exception e) {
                logger.error("Failed to fetch {} events for entity {}", entityType, entityId, e);
                throw new RuntimeException("Failed to fetch entity events", e);
            }
        });
    }

    public CompletableFuture<List<AlarmHistoryRecord>> getAlarmHistory(
            String alarmId, String simulationId, Instant from, Instant to) {
        String sql = """
                SELECT event_id, alarm_id, conveyor_id, event_type, severity, typology,
                       stops_conveyor, event_timestamp, simulation_id
                FROM analytics_alarm_events FINAL
                WHERE simulation_id = {simulationId:String}
                  AND ({alarmId:String} = '' OR alarm_id = {alarmId:String})
                  AND event_timestamp >= {from:DateTime64(3)}
                  AND event_timestamp <= {to:DateTime64(3)}
                ORDER BY event_timestamp, event_id
                FORMAT JSONEachRow
                SETTINGS date_time_output_format = 'iso', output_format_json_quote_64bit_integers = 0
                """;
        return CompletableFuture.supplyAsync(() -> {
            List<AlarmHistoryRecord> alarms = new ArrayList<>();
            try (QueryResponse response = client.query(sql, Map.of(
                    "simulationId", simulationId != null ? simulationId : "",
                    "alarmId", alarmId != null ? alarmId : "",
                    "from", CLICKHOUSE_FORMATTER.format(from),
                    "to", CLICKHOUSE_FORMATTER.format(to))).get();
                    InputStream input = response.getInputStream()) {
                var mapType = objectMapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class);
                MappingIterator<Map<String, Object>> rows = objectMapper.readerFor(mapType).readValues(input);
                while (rows.hasNext()) {
                    Map<String, Object> row = rows.next();
                    alarms.add(new AlarmHistoryRecord(
                            String.valueOf(row.get("event_id")),
                            String.valueOf(row.get("alarm_id")),
                            String.valueOf(row.get("conveyor_id")),
                            String.valueOf(row.get("event_type")),
                            AlarmSeverity.valueOf(String.valueOf(row.get("severity"))),
                            String.valueOf(row.get("typology")),
                            asBoolean(row.get("stops_conveyor")),
                            parseClickHouseInstant(row.get("event_timestamp")),
                            String.valueOf(row.get("simulation_id"))));
                }
                return alarms;
            } catch (Exception e) {
                throw new IllegalStateException("Failed to read alarm history", e);
            }
        });
    }

    public CompletableFuture<List<String>> getAlarmAffectedItems(String alarmId, String simulationId) {
        String sql = """
                SELECT DISTINCT item_id
                FROM analytics_alarm_affected_items FINAL
                WHERE simulation_id = {simulationId:String} AND alarm_id = {alarmId:String}
                ORDER BY item_id
                FORMAT JSONEachRow
                """;
        return CompletableFuture.supplyAsync(() -> {
            List<String> itemIds = new ArrayList<>();
            try (QueryResponse response = client.query(sql, Map.of(
                    "simulationId", simulationId != null ? simulationId : "",
                    "alarmId", alarmId)).get();
                    InputStream input = response.getInputStream()) {
                var mapType = objectMapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class);
                MappingIterator<Map<String, Object>> rows = objectMapper.readerFor(mapType).readValues(input);
                while (rows.hasNext()) {
                    itemIds.add(String.valueOf(rows.next().get("item_id")));
                }
                return itemIds;
            } catch (Exception e) {
                throw new IllegalStateException("Failed to read alarm affected items", e);
            }
        });
    }

    public CompletableFuture<List<BadActorMetric>> getTopActiveComponents(int limit) {
        String simulationId = currentSimulationScope();
        int boundedLimit = Math.max(1, limit);
        String sql = """
                    SELECT
                        location_id,
                        sum(total_items_passed) AS total_items_passed,
                        max(last_activity) AS last_activity
                    FROM %s.analytics_components
                    WHERE simulation_id = {simulationId:String}
                    GROUP BY location_id
                    ORDER BY total_items_passed DESC
                    LIMIT %d
                    FORMAT JSONEachRow
                """.formatted(clickhouseDatabase, boundedLimit);

        return CompletableFuture.supplyAsync(() -> {
            List<BadActorMetric> metrics = new ArrayList<>();
            try (QueryResponse response = client.query(sql, Map.of("simulationId", simulationId)).get()) {
                try (InputStream inputStream = response.getInputStream()) {
                    MappingIterator<Map<String, Object>> it = objectMapper
                            .readerFor(Map.class)
                            .readValues(inputStream);

                    while (it.hasNext()) {
                        Map<String, Object> row = it.next();
                        metrics.add(new BadActorMetric(
                                (String) row.get("location_id"),
                                (String) row.get("location_id"),
                                asLong(row.get("total_items_passed")),
                                0.0));
                    }
                }
            } catch (Exception e) {
                logger.error("Failed to fetch top components", e);
                return new ArrayList<>();
            }
            return metrics;
        });
    }

    public synchronized void saveMetricAsync(MetricEvent metric) {
        enqueueOrThrow(metricQueue, metric, "component metric");
        // If queue gets too big, force flush immediately
        if (metricQueue.size() >= BATCH_SIZE) {
            flushMetrics();
        }
    }

    public synchronized void flushMetrics() {
        if (metricQueue.isEmpty())
            return;

        List<MetricEvent> batch = new ArrayList<>();
        metricQueue.drainTo(batch, BATCH_SIZE);

        // Convert to Maps for your existing JSON insert logic
        List<Map<String, Object>> rows = batch.stream().map(m -> {
            Map<String, Object> row = new HashMap<>();
            row.put("timestamp", CLICKHOUSE_FORMATTER.format(m.timestamp()));
            row.put("simulation_id", m.simulationId());
            row.put("component_id", m.componentId());
            row.put("component_type", m.componentType());
            row.put("metric_type", m.metricType());
            row.put("value", m.value());
            return row;
        }).toList();

        try {
            saveMetricSnapshots(rows); // Reuse existing bulk insert method
        } catch (RuntimeException e) {
            for (MetricEvent metric : batch) {
                metricQueue.offer(metric);
            }
            logger.error("Requeued {} component metrics after a ClickHouse flush failure", batch.size(), e);
        }
    }

    public void saveMetricSnapshots(List<Map<String, Object>> metrics) {
        if (metrics == null || metrics.isEmpty()) {
            return;
        }

        try {
            StringBuilder jsonBatch = new StringBuilder();

            for (Map<String, Object> row : metrics) {
                // Ensure timestamp is formatted correctly if passed as Instant/Date object
                if (row.get("timestamp") instanceof Instant instant) {
                    row.put("timestamp", CLICKHOUSE_FORMATTER.format(instant));
                }

                jsonBatch.append(objectMapper.writeValueAsString(row)).append("\n");
            }

            // Perform a SINGLE insert for the whole batch
            try (var inputStream = new ByteArrayInputStream(jsonBatch.toString().getBytes(StandardCharsets.UTF_8))) {
                client.insert("ComponentMetrics", inputStream, ClickHouseFormat.JSONEachRow).get();
            }

            logger.debug("Flushed {} metric snapshots to ClickHouse", metrics.size());

        } catch (Exception e) {
            logger.error("Error flushing metrics batch to ClickHouse.", e);
            throw new RuntimeException("Failed to save metrics", e);
        }
    }

    private record PathTraversalMetric(
            Instant timestamp,
            String simulationId,
            String itemId,
            String previousPositionId,
            String previousPositionType,
            String newPositionId,
            String newPositionType,
            List<String> path) {
    }

    private record RecirculationFact(
            String id,
            Instant timestamp,
            String simulationId,
            String itemId,
            List<String> previousPath,
            List<String> newPath) {
    }

    void drainOnShutdown() {
        drainPathTraversalMetricsOnShutdown();
        drainLocationTransitMetricsOnShutdown();
        drainQueueOnShutdown("exit candidate", exitCandidateQueue, this::flushExitCandidates);
        drainQueueOnShutdown("recirculation", recirculationQueue, this::flushRecirculationFacts);
        drainQueueOnShutdown("simulation connection", simulationConnectionQueue, this::flushSimulationConnectionSignals);
        drainQueueOnShutdown("component metric", metricQueue, this::flushMetrics);
    }

}
