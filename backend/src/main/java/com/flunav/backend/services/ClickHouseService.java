package com.flunav.backend.services;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.query.QueryResponse;
import com.clickhouse.data.ClickHouseFormat;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.flunav.backend.context.DatabaseContextHolder;
import com.flunav.backend.models.analytics.EntityEventType;
import com.flunav.backend.models.analytics.ConnectionStateSignal;
import com.flunav.backend.models.analytics.ExitCandidate;
import com.flunav.backend.models.analytics.LocationTransitMetric;
import com.flunav.backend.models.analytics.MetricEvent;
import com.flunav.backend.models.graph.GraphData;
import com.flunav.backend.models.response.BadActorMetric;
import com.flunav.backend.models.response.EntityEventRecord;
import com.flunav.backend.models.response.JourneySummary;
import com.flunav.backend.models.response.ThroughputMetric;
import com.flunav.backend.models.response.AlarmHistoryRecord;
import flunav.types.AlarmSeverity;

import flunav.events.DomainEvent;
import flunav.events.EntityEvent;
import flunav.events.ConnectionActivatedEvent;
import flunav.events.ConnectionCreatedEvent;
import flunav.events.ConnectionDeactivatedEvent;
import flunav.events.ConnectionDeletedEvent;
import flunav.events.ConnectionSpeedChangedEvent;
import flunav.events.PathTraversedEvent;
import flunav.events.AlarmRaisedEvent;
import flunav.events.AlarmClearedEvent;
import flunav.events.UnknownEvent;
import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.LinkedBlockingQueue;

@Service
public class ClickHouseService {
    private static final Logger logger = LoggerFactory.getLogger(ClickHouseService.class);
    private final Client client;
    private final ObjectMapper objectMapper;
    private final String clickhouseDatabase;
    private static final DateTimeFormatter CLICKHOUSE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
            .withZone(ZoneOffset.UTC);
    private static final int MAX_QUEUE_SIZE = 100_000;
    private final LinkedBlockingDeque<DomainEvent> eventQueue = new LinkedBlockingDeque<>(MAX_QUEUE_SIZE);
    private static final int BATCH_SIZE = 1000;

    public ClickHouseService(
            @Value("${clickhouse.url}") String clickhouseUrl,
            @Value("${clickhouse.username}") String username,
            @Value("${clickhouse.password}") String password,
            ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.clickhouseDatabase = extractDatabase(clickhouseUrl);
        try {
            this.client = new Client.Builder()
                    .addEndpoint(clickhouseUrl)
                    .setUsername(username)
                    .setPassword(password)
                    .build();
            ensureAnalyticsSchema();
            ensureMovementAnalyticsSchema();
            ensureOperationalAnalyticsSchema();
            ensureAlarmAnalyticsSchema();
            logger.info("ClickHouse Client V2 initialized successfully.");
        } catch (Exception e) {
            logger.error("Failed to initialize ClickHouse client", e);
            throw new RuntimeException("ClickHouse init error", e);
        }
    }

    @PreDestroy
    public void cleanup() {
        drainAllQueuesOnShutdown();
        if (client != null) {
            client.close();
        }
    }

    public synchronized void saveEventAsync(DomainEvent event) {
        if (event instanceof PathTraversedEvent pathTraversedEvent) {
            savePathTraversalMetricAsync(pathTraversedEvent);
            return;
        }

        enqueueOrThrow(eventQueue, event, "event");

        if (eventQueue.size() >= BATCH_SIZE) {
            flushEvents();
        }
    }

    @Scheduled(fixedRate = 1000)
    public synchronized void flushEvents() {
        if (eventQueue.isEmpty()) {
            return;
        }

        List<DomainEvent> batch = new ArrayList<>();
        // Drain the queue into a local list to unblock the queue for new incoming
        // events
        eventQueue.drainTo(batch, BATCH_SIZE);

        if (batch.isEmpty())
            return;

        try {
            insertEventBatch(batch);
            logger.debug("Flushed {} events to ClickHouse", batch.size());

        } catch (Exception e) {
            requeueBatch(batch);
            logger.error("Error flushing batch to ClickHouse. Events might be lost!", e);
        }
    }

    private void requeueBatch(List<DomainEvent> batch) {
        for (int i = batch.size() - 1; i >= 0; i--) {
            eventQueue.offerFirst(batch.get(i));
        }
    }

    private void insertEventBatch(List<DomainEvent> batch) throws Exception {
        if (batch == null || batch.isEmpty()) {
            return;
        }
        Set<String> persistedEventIds = findPersistedEventIds(batch);
        List<DomainEvent> newEvents = batch.stream()
                .filter(event -> !persistedEventIds.contains(event.getEventId()))
                .toList();
        if (newEvents.isEmpty()) {
            return;
        }
        StringBuilder jsonBatch = new StringBuilder();
        Instant processedTimestamp = Instant.now();
        for (DomainEvent event : newEvents) {
            Map<String, Object> clickHouseRow = new HashMap<>();
            clickHouseRow.put("event_type", event.getEventType());
            clickHouseRow.put("event_id", event.getEventId());
            clickHouseRow.put("data", event);
            clickHouseRow.put("timestamp_received", CLICKHOUSE_FORMATTER.format(event.getTimestamp()));
            clickHouseRow.put("timestamp_processed", CLICKHOUSE_FORMATTER.format(processedTimestamp));
            clickHouseRow.put("entity_id", event instanceof EntityEvent entityEvent
                    ? entityEvent.getEntityId()
                    : null);
            jsonBatch.append(objectMapper.writeValueAsString(clickHouseRow)).append('\n');
        }
        try (var inputStream = new ByteArrayInputStream(jsonBatch.toString().getBytes(StandardCharsets.UTF_8))) {
            client.insert("Events", inputStream, ClickHouseFormat.JSONEachRow).get();
        }
    }

    /**
     * Checks event ids before each insert so a retry after an ambiguous client
     * timeout cannot append a second physical row for a batch already committed by
     * ClickHouse.
     */
    private Set<String> findPersistedEventIds(List<DomainEvent> batch) throws Exception {
        String eventIds = batch.stream()
                .map(DomainEvent::getEventId)
                .map(UUID::fromString)
                .map(UUID::toString)
                .map(id -> "'" + id + "'")
                .collect(java.util.stream.Collectors.joining(","));
        String query = "SELECT toString(event_id) AS event_id FROM Events WHERE event_id IN ("
                + eventIds + ") FORMAT JSONEachRow";
        Set<String> existing = new HashSet<>();
        try (QueryResponse response = client.query(query).get();
                InputStream inputStream = response.getInputStream()) {
            MappingIterator<Map<String, Object>> rows = objectMapper.readerFor(Map.class).readValues(inputStream);
            while (rows.hasNext()) {
                existing.add(Objects.toString(rows.next().get("event_id"), ""));
            }
        }
        return existing;
    }

    /**
     * Drains every pending event batch or fails the caller before a graph snapshot
     * is accepted. This provides a ClickHouse high-water mark while live reducers
     * are held behind EventProcessor's snapshot barrier.
     */
    public synchronized void flushAllEventsOrThrow() {
        while (!eventQueue.isEmpty()) {
            List<DomainEvent> batch = new ArrayList<>();
            eventQueue.drainTo(batch, BATCH_SIZE);
            try {
                insertEventBatch(batch);
            } catch (Exception e) {
                requeueBatch(batch);
                throw new IllegalStateException("Unable to drain events before snapshot", e);
            }
        }
    }

    private void drainAllQueuesOnShutdown() {
        try {
            flushAllEventsOrThrow();
        } catch (Exception e) {
            logger.error("Unable to drain all event batches during shutdown", e);
        }
        drainPathTraversalMetricsOnShutdown();
        drainLocationTransitMetricsOnShutdown();
        drainQueueOnShutdown("exit candidate", exitCandidateQueue, this::flushExitCandidates);
        drainQueueOnShutdown("recirculation", recirculationQueue, this::flushRecirculationFacts);
        drainQueueOnShutdown("simulation connection", simulationConnectionQueue,
                this::flushSimulationConnectionSignals);
        drainQueueOnShutdown("component metric", metricQueue, this::flushMetrics);
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

    /**
     * Repeatedly flushes one bounded analytics queue during shutdown. A flush that
     * makes no progress indicates an outage, so shutdown stops retrying instead of
     * spinning forever while preserving the remaining in-memory batch for logs.
     */
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

    private final LinkedBlockingDeque<PathTraversalMetric> pathTraversalQueue = new LinkedBlockingDeque<>(MAX_QUEUE_SIZE);

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

    @Scheduled(fixedRate = 1000)
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

    private final LinkedBlockingDeque<LocationTransitMetric> locationTransitQueue = new LinkedBlockingDeque<>(MAX_QUEUE_SIZE);

    public synchronized void saveLocationTransitMetricAsync(LocationTransitMetric metric) {
        String simulationId = metric.simulationId() != null ? metric.simulationId() : currentSimulationScope();
        enqueueOrThrow(locationTransitQueue, new LocationTransitMetric(
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

    @Scheduled(fixedRate = 1000)
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

    private final LinkedBlockingDeque<ExitCandidate> exitCandidateQueue = new LinkedBlockingDeque<>(MAX_QUEUE_SIZE);

    public synchronized void saveExitCandidateAsync(ExitCandidate candidate) {
        enqueueOrThrow(exitCandidateQueue, candidate, "exit candidate");
        if (exitCandidateQueue.size() >= BATCH_SIZE) {
            flushExitCandidates();
        }
    }

    @Scheduled(fixedRate = 1000)
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

    /**
     * Resolves queued exits independently of chute reduction. Candidates without a
     * visible creation event remain in ClickHouse and are retried after the live
     * event batch has had time to flush.
     */
    @Scheduled(fixedDelay = 1000)
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

    private record RecirculationFact(
            String id,
            Instant timestamp,
            String simulationId,
            String itemId,
            List<String> previousPath,
            List<String> newPath) {
    }

    private final LinkedBlockingDeque<RecirculationFact> recirculationQueue = new LinkedBlockingDeque<>(MAX_QUEUE_SIZE);

    public synchronized void saveRecirculationAsync(String id, Instant timestamp, String simulationId, String itemId,
            List<String> previousPath, List<String> newPath) {
        enqueueOrThrow(recirculationQueue, new RecirculationFact(
                id, timestamp, simulationId, itemId, List.copyOf(previousPath), List.copyOf(newPath)),
                "recirculation");
        if (recirculationQueue.size() >= BATCH_SIZE) {
            flushRecirculationFacts();
        }
    }

    @Scheduled(fixedRate = 1000)
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

    private final LinkedBlockingDeque<ConnectionStateSignal> simulationConnectionQueue = new LinkedBlockingDeque<>(MAX_QUEUE_SIZE);

    public synchronized void saveSimulationConnectionSignalAsync(ConnectionStateSignal signal) {
        enqueueOrThrow(simulationConnectionQueue, signal, "simulation connection");
        if (simulationConnectionQueue.size() >= BATCH_SIZE) {
            flushSimulationConnectionSignals();
        }
    }

    @Scheduled(fixedRate = 1000)
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

    /**
     * Saves a graph snapshot to the 'snapshots' table in ClickHouse.
     * 
     * @param snapshotId The unique ID for the snapshot.
     * @param timestamp  The time the snapshot was taken.
     * @param graphData  The graph data object to be serialized and stored.
     */
    public void saveSnapshot(String snapshotId, Instant timestamp, GraphData graphData) {
        try {
            // Create a Map that directly matches the 'snapshots' table columns.
            Map<String, Object> clickHouseRow = new HashMap<>();

            clickHouseRow.put("snapshot_id", snapshotId);
            clickHouseRow.put("timestamp", CLICKHOUSE_FORMATTER.format(timestamp));

            clickHouseRow.put("graph_data", graphData);

            // Serialize the entire row map into a single JSON string for insertion.
            String finalJson = objectMapper.writeValueAsString(clickHouseRow);

            // Insert into the 'snapshots' table using the JSONEachRow format.
            try (var inputStream = new ByteArrayInputStream(finalJson.getBytes(StandardCharsets.UTF_8))) {
                client.insert("snapshots", inputStream, ClickHouseFormat.JSONEachRow).get();
            }

            logger.info("Successfully saved graph snapshot with ID: {}", snapshotId);

        } catch (Exception e) {
            logger.error("Error saving snapshot {} to ClickHouse", snapshotId, e);
            throw new RuntimeException("Snapshot save failed", e);
        }
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

    /**
     * Ensures persisted throughput data uses the bucket schema expected by the
     * application. Legacy tables are archived rather than dropped so deployment can
     * proceed without silently querying incompatible columns or destroying data.
     */
    private void ensureAnalyticsSchema() {
        try {
            String columnsSql = """
                    SELECT name, type
                    FROM system.columns
                    WHERE database = {database:String}
                      AND table = 'analytics_time_series'
                    FORMAT JSONEachRow
                    """;
            Map<String, String> columns = new HashMap<>();
            try (QueryResponse response = client.query(columnsSql, Map.of("database", clickhouseDatabase)).get();
                    InputStream inputStream = response.getInputStream()) {
                MappingIterator<Map<String, Object>> rows = objectMapper.readerFor(Map.class).readValues(inputStream);
                while (rows.hasNext()) {
                    Map<String, Object> row = rows.next();
                    columns.put(String.valueOf(row.get("name")), String.valueOf(row.get("type")));
                }
            }

            boolean valid = columns.size() == 5
                    && "DateTime64(3)".equals(columns.get("bucket_start"))
                    && "UInt16".equals(columns.get("bucket_seconds"))
                    && "UInt64".equals(columns.get("items_entered"))
                    && "UInt64".equals(columns.get("items_exited"))
                    && "UInt64".equals(columns.get("items_current"));

            if (!columns.isEmpty() && !valid) {
                String archive = "analytics_time_series_legacy_" + Instant.now().toEpochMilli();
                executeClickHouseStatement("RENAME TABLE " + clickhouseDatabase + ".analytics_time_series TO "
                        + clickhouseDatabase + "." + archive);
                logger.warn("Archived incompatible analytics table as {}.{}", clickhouseDatabase, archive);
            }

            if (!valid) {
                executeClickHouseStatement("""
                        CREATE TABLE IF NOT EXISTS %s.analytics_time_series
                        (
                            bucket_start DateTime64(3),
                            bucket_seconds UInt16,
                            items_entered UInt64,
                            items_exited UInt64,
                            items_current UInt64
                        )
                        ENGINE = MergeTree()
                        PARTITION BY toYYYYMM(bucket_start)
                        ORDER BY (bucket_start, bucket_seconds)
                        """.formatted(clickhouseDatabase));
            }
        } catch (Exception e) {
            throw new IllegalStateException("Unable to initialize ClickHouse analytics schema", e);
        }
    }

    private void ensureMovementAnalyticsSchema() {
        try {
            dropAnalyticsMaterializedViews();
            archiveTableIfMissingColumns("analytics_path_traversal_ingest", List.of("simulation_id"));
            archiveTableIfMissingColumns("item_journeys", List.of("simulation_id"));
            archiveTableIfMissingColumns("analytics_components", List.of("simulation_id"));
            archiveTableIfMissingColumns("analytics_location_transit_events", List.of("simulation_id"));
            archiveTableIfMissingColumns("analytics_location_transit_counts", List.of("simulation_id"));
            archiveTableIfMissingColumns("analytics_path_transit_stats", List.of("simulation_id"));

            executeClickHouseStatement("""
                    CREATE TABLE IF NOT EXISTS %s.analytics_path_traversal_ingest
                    (
                        event_timestamp DateTime64(3),
                        simulation_id LowCardinality(String),
                        item_id String,
                        previous_position_id String,
                        previous_position_type LowCardinality(String),
                        new_position_id String,
                        new_position_type LowCardinality(String),
                        path Array(String)
                    )
                    ENGINE = Null
                    """.formatted(clickhouseDatabase));

            executeClickHouseStatement("""
                    CREATE TABLE IF NOT EXISTS %s.item_journeys
                    (
                        simulation_id LowCardinality(String),
                        item_id String,
                        first_seen SimpleAggregateFunction(min, DateTime64(3)),
                        last_seen SimpleAggregateFunction(max, DateTime64(3)),
                        path_segments AggregateFunction(groupArrayArray, Array(String))
                    )
                    ENGINE = AggregatingMergeTree()
                    ORDER BY (simulation_id, item_id)
                    """.formatted(clickhouseDatabase));

            executeClickHouseStatement("""
                    CREATE MATERIALIZED VIEW IF NOT EXISTS %s.mv_item_journeys
                    TO %s.item_journeys
                    AS
                    SELECT
                        simulation_id,
                        item_id,
                        min(event_timestamp) AS first_seen,
                        max(event_timestamp) AS last_seen,
                        groupArrayArrayState(path) AS path_segments
                    FROM %s.analytics_path_traversal_ingest
                    GROUP BY simulation_id, item_id
                    """.formatted(clickhouseDatabase, clickhouseDatabase, clickhouseDatabase));

            executeClickHouseStatement("""
                    CREATE TABLE IF NOT EXISTS %s.analytics_components
                    (
                        simulation_id LowCardinality(String),
                        location_id LowCardinality(String),
                        total_items_passed UInt64,
                        last_activity SimpleAggregateFunction(max, DateTime64(3))
                    )
                    ENGINE = SummingMergeTree()
                    ORDER BY (simulation_id, location_id)
                    """.formatted(clickhouseDatabase));

            executeClickHouseStatement("""
                    CREATE MATERIALIZED VIEW IF NOT EXISTS %s.mv_analytics_components
                    TO %s.analytics_components
                    AS
                    SELECT
                        simulation_id,
                        arrayJoin(path) AS location_id,
                        count() AS total_items_passed,
                        max(event_timestamp) AS last_activity
                    FROM %s.analytics_path_traversal_ingest
                    GROUP BY simulation_id, location_id
                    """.formatted(clickhouseDatabase, clickhouseDatabase, clickhouseDatabase));

            executeClickHouseStatement("""
                    CREATE TABLE IF NOT EXISTS %s.analytics_location_transit_events
                    (
                        event_timestamp DateTime64(3),
                        simulation_id LowCardinality(String),
                        item_id String,
                        from_location_id LowCardinality(String),
                        to_location_id LowCardinality(String),
                        from_position_id String,
                        from_position_type LowCardinality(String),
                        to_position_id String,
                        to_position_type LowCardinality(String),
                        transit_time_ms UInt64,
                        path Array(String)
                    )
                    ENGINE = MergeTree()
                    PARTITION BY toYYYYMM(event_timestamp)
                    ORDER BY (simulation_id, from_location_id, to_location_id, event_timestamp, item_id)
                    """.formatted(clickhouseDatabase));

            executeClickHouseStatement("""
                    CREATE TABLE IF NOT EXISTS %s.analytics_location_transit_counts
                    (
                        simulation_id LowCardinality(String),
                        location_id LowCardinality(String),
                        items_transited UInt64,
                        last_activity SimpleAggregateFunction(max, DateTime64(3))
                    )
                    ENGINE = SummingMergeTree()
                    ORDER BY (simulation_id, location_id)
                    """.formatted(clickhouseDatabase));

            executeClickHouseStatement("""
                    CREATE MATERIALIZED VIEW IF NOT EXISTS %s.mv_analytics_location_transit_counts
                    TO %s.analytics_location_transit_counts
                    AS
                    SELECT
                        simulation_id,
                        to_location_id AS location_id,
                        count() AS items_transited,
                        max(event_timestamp) AS last_activity
                    FROM %s.analytics_location_transit_events
                    GROUP BY simulation_id, location_id
                    """.formatted(clickhouseDatabase, clickhouseDatabase, clickhouseDatabase));

            executeClickHouseStatement("""
                    CREATE TABLE IF NOT EXISTS %s.analytics_path_transit_stats
                    (
                        simulation_id LowCardinality(String),
                        from_location_id LowCardinality(String),
                        to_location_id LowCardinality(String),
                        sample_count_state AggregateFunction(count),
                        avg_transit_time_ms_state AggregateFunction(avg, Float64),
                        stddev_transit_time_ms_state AggregateFunction(stddevPop, Float64),
                        median_transit_time_ms_state AggregateFunction(quantile(0.5), Float64),
                        min_transit_time_ms SimpleAggregateFunction(min, UInt64),
                        max_transit_time_ms SimpleAggregateFunction(max, UInt64),
                        last_activity SimpleAggregateFunction(max, DateTime64(3))
                    )
                    ENGINE = AggregatingMergeTree()
                    ORDER BY (simulation_id, from_location_id, to_location_id)
                    """.formatted(clickhouseDatabase));

            executeClickHouseStatement("""
                    CREATE MATERIALIZED VIEW IF NOT EXISTS %s.mv_analytics_path_transit_stats
                    TO %s.analytics_path_transit_stats
                    AS
                    SELECT
                        simulation_id,
                        from_location_id,
                        to_location_id,
                        countState() AS sample_count_state,
                        avgState(toFloat64(transit_time_ms)) AS avg_transit_time_ms_state,
                        stddevPopState(toFloat64(transit_time_ms)) AS stddev_transit_time_ms_state,
                        quantileState(0.5)(toFloat64(transit_time_ms)) AS median_transit_time_ms_state,
                        min(transit_time_ms) AS min_transit_time_ms,
                        max(transit_time_ms) AS max_transit_time_ms,
                        max(event_timestamp) AS last_activity
                    FROM %s.analytics_location_transit_events
                    GROUP BY simulation_id, from_location_id, to_location_id
                    """.formatted(clickhouseDatabase, clickhouseDatabase, clickhouseDatabase));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to initialize ClickHouse movement analytics schema", e);
        }
    }

    /**
     * Creates retry-safe operational fact tables when an older deployment starts
     * against a ClickHouse database that predates the analytics migration.
     */
    private void ensureOperationalAnalyticsSchema() {
        try {
            executeClickHouseStatement("""
                    CREATE TABLE IF NOT EXISTS analytics_exit_candidates
                    (
                        candidate_id String,
                        exit_event_id String,
                        item_id String,
                        chute_id String,
                        exit_timestamp DateTime64(3, 'UTC'),
                        simulation_id LowCardinality(String),
                        simulation_created_at Nullable(DateTime64(3, 'UTC')),
                        live_history_cutoff Nullable(DateTime64(3, 'UTC')),
                        version DateTime64(3, 'UTC') DEFAULT now64(3)
                    ) ENGINE = ReplacingMergeTree(version)
                    ORDER BY (simulation_id, candidate_id)
                    """);
            executeClickHouseStatement("""
                    CREATE TABLE IF NOT EXISTS analytics_completed_journeys
                    (
                        journey_id String,
                        item_id String,
                        chute_id String,
                        created_timestamp DateTime64(3, 'UTC'),
                        exit_timestamp DateTime64(3, 'UTC'),
                        traversal_time_ms UInt64,
                        simulation_id LowCardinality(String),
                        version DateTime64(3, 'UTC') DEFAULT now64(3)
                    ) ENGINE = ReplacingMergeTree(version)
                    ORDER BY (simulation_id, journey_id)
                    """);
            executeClickHouseStatement("""
                    CREATE TABLE IF NOT EXISTS analytics_recirculation_facts
                    (
                        recirculation_id String,
                        event_timestamp DateTime64(3, 'UTC'),
                        simulation_id LowCardinality(String),
                        item_id String,
                        previous_path Array(String),
                        new_path Array(String),
                        version DateTime64(3, 'UTC') DEFAULT now64(3)
                    ) ENGINE = ReplacingMergeTree(version)
                    ORDER BY (simulation_id, recirculation_id)
                    """);
            executeClickHouseStatement("""
                    CREATE TABLE IF NOT EXISTS analytics_simulation_connection_events
                    (
                        event_id String,
                        event_timestamp DateTime64(3, 'UTC'),
                        simulation_id LowCardinality(String),
                        conveyor_id String,
                        event_type LowCardinality(String),
                        active Nullable(UInt8),
                        speed Nullable(Float64),
                        source_id String,
                        target_id String,
                        version DateTime64(3, 'UTC') DEFAULT now64(3)
                    ) ENGINE = ReplacingMergeTree(version)
                    ORDER BY (simulation_id, event_id)
                    """);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to ensure operational analytics schema", e);
        }
    }

    private void ensureAlarmAnalyticsSchema() {
        try {
            executeClickHouseStatement("""
                    CREATE TABLE IF NOT EXISTS analytics_alarm_events
                    (
                        event_id String,
                        alarm_id String,
                        conveyor_id String,
                        event_type LowCardinality(String),
                        severity LowCardinality(String),
                        typology String,
                        stops_conveyor UInt8,
                        event_timestamp DateTime64(3, 'UTC'),
                        simulation_id LowCardinality(String),
                        version DateTime64(3, 'UTC') DEFAULT now64(3)
                    ) ENGINE = ReplacingMergeTree(version)
                    ORDER BY (simulation_id, event_id)
                    """);
            executeClickHouseStatement("""
                    CREATE TABLE IF NOT EXISTS analytics_alarm_affected_items
                    (
                        event_id String,
                        alarm_id String,
                        conveyor_id String,
                        item_id String,
                        captured_at DateTime64(3, 'UTC'),
                        simulation_id LowCardinality(String),
                        version DateTime64(3, 'UTC') DEFAULT now64(3)
                    ) ENGINE = ReplacingMergeTree(version)
                    ORDER BY (simulation_id, alarm_id, item_id)
                    """);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to ensure alarm analytics schema", e);
        }
    }

    /**
     * Writes the immutable alarm fact after its state reducer succeeds. Simulation
     * facts carry their namespace and are removed with the simulation.
     */
    public synchronized void saveAlarmFact(DomainEvent event, String simulationId, List<String> affectedItemIds) {
        if (!(event instanceof AlarmRaisedEvent) && !(event instanceof AlarmClearedEvent)) {
            return;
        }
        String alarmId;
        String conveyorId;
        String severity;
        String typology;
        boolean stopsConveyor;
        if (event instanceof AlarmRaisedEvent raised) {
            alarmId = raised.getAlarmId();
            conveyorId = raised.getConveyorId();
            severity = raised.getSeverity().name();
            typology = raised.getTypology();
            stopsConveyor = raised.isStopsConveyor();
        } else {
            AlarmClearedEvent cleared = (AlarmClearedEvent) event;
            alarmId = cleared.getAlarmId();
            conveyorId = cleared.getConveyorId();
            severity = cleared.getSeverity().name();
            typology = cleared.getTypology();
            stopsConveyor = cleared.isStopsConveyor();
        }

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("event_id", event.getEventId());
        row.put("alarm_id", alarmId);
        row.put("conveyor_id", conveyorId);
        row.put("event_type", event.getEventType());
        row.put("severity", severity);
        row.put("typology", typology);
        row.put("stops_conveyor", stopsConveyor ? 1 : 0);
        row.put("event_timestamp", CLICKHOUSE_FORMATTER.format(event.getTimestamp()));
        row.put("simulation_id", simulationId != null ? simulationId : "");
        insertJsonRows("analytics_alarm_events", List.of(row));

        if (event instanceof AlarmRaisedEvent && stopsConveyor && affectedItemIds != null
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

    private void insertJsonRows(String table, List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return;
        }
        try {
            StringBuilder body = new StringBuilder();
            for (Map<String, Object> row : rows) {
                body.append(objectMapper.writeValueAsString(row)).append('\n');
            }
            try (InputStream input = new ByteArrayInputStream(body.toString().getBytes(StandardCharsets.UTF_8))) {
                client.insert(table, input, ClickHouseFormat.JSONEachRow).get();
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to write " + table, e);
        }
    }

    private void dropAnalyticsMaterializedViews() throws Exception {
        executeClickHouseStatement("DROP TABLE IF EXISTS " + clickhouseDatabase + ".mv_item_journeys");
        executeClickHouseStatement("DROP TABLE IF EXISTS " + clickhouseDatabase + ".mv_analytics_components");
        executeClickHouseStatement("DROP TABLE IF EXISTS " + clickhouseDatabase + ".mv_analytics_location_transit_counts");
        executeClickHouseStatement("DROP TABLE IF EXISTS " + clickhouseDatabase + ".mv_analytics_path_transit_stats");
    }

    private void archiveTableIfMissingColumns(String tableName, List<String> requiredColumns) throws Exception {
        Map<String, String> columns = tableColumns(tableName);
        if (columns.isEmpty()) {
            return;
        }

        boolean compatible = requiredColumns.stream().allMatch(columns::containsKey);
        if (compatible) {
            return;
        }

        String archive = tableName + "_legacy_" + Instant.now().toEpochMilli();
        executeClickHouseStatement("RENAME TABLE " + clickhouseDatabase + "." + tableName + " TO "
                + clickhouseDatabase + "." + archive);
        logger.warn("Archived incompatible ClickHouse analytics table as {}.{}", clickhouseDatabase, archive);
    }

    private void executeClickHouseStatement(String sql) throws Exception {
        try (QueryResponse ignored = client.query(sql).get()) {
        }
    }

    private Map<String, String> tableColumns(String tableName) throws Exception {
        String columnsSql = """
                SELECT name, type
                FROM system.columns
                WHERE database = {database:String}
                  AND table = {table:String}
                FORMAT JSONEachRow
                """;
        Map<String, String> columns = new HashMap<>();
        try (QueryResponse response = client.query(columnsSql, Map.of(
                "database", clickhouseDatabase,
                "table", tableName)).get();
                InputStream inputStream = response.getInputStream()) {
            MappingIterator<Map<String, Object>> rows = objectMapper.readerFor(Map.class).readValues(inputStream);
            while (rows.hasNext()) {
                Map<String, Object> row = rows.next();
                columns.put(String.valueOf(row.get("name")), String.valueOf(row.get("type")));
            }
        }
        return columns;
    }

    private String extractDatabase(String clickhouseUrl) {
        try {
            String path = URI.create(clickhouseUrl).getPath();
            String database = path == null || path.isBlank() || "/".equals(path)
                    ? "default"
                    : path.substring(path.lastIndexOf('/') + 1);
            if (!database.matches("[A-Za-z0-9_]+")) {
                throw new IllegalArgumentException("Invalid ClickHouse database name");
            }
            return database;
        } catch (Exception e) {
            throw new IllegalArgumentException("ClickHouse URL must identify a valid database", e);
        }
    }

    /**
     * Retrieves the most recent graph snapshot from ClickHouse at or before a given
     * point in time.
     * The method returns a Snapshot object containing both the graph data and its
     * timestamp.
     *
     * @param timestamp The point in time to find the latest snapshot for.
     * @return An Optional containing the Snapshot if one is found, otherwise an
     *         empty Optional.
     */
    public Optional<Snapshot> getMostRecentSnapshotBefore(Instant timestamp) {
        String formattedTimestamp = CLICKHOUSE_FORMATTER.format(timestamp);
        String query = """
                SELECT graph_data, timestamp
                FROM snapshots
                WHERE timestamp <= {ts:DateTime64(3, 'UTC')}
                ORDER BY timestamp DESC
                LIMIT 1
                FORMAT JSONEachRow
                SETTINGS
                    date_time_output_format = 'iso',
                    output_format_json_quote_64bit_integers = 0
                """;

        logger.debug("Executing query to find most recent snapshot before {}", formattedTimestamp);

        try (QueryResponse response = client.query(query, Map.of("ts", formattedTimestamp)).get()) {

            try (InputStream inputStream = response.getInputStream()) {

                var mapType = objectMapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class);

                try {
                    Map<String, Object> row = objectMapper.readValue(inputStream, mapType);

                    GraphData graphData = objectMapper.convertValue(row.get("graph_data"), GraphData.class);
                    String timestampString = (String) row.get("timestamp");
                    Instant snapshotTimestamp = Instant.parse(timestampString);

                    Snapshot result = new Snapshot(graphData, snapshotTimestamp);

                    logger.info("Successfully retrieved and deserialized snapshot.");
                    return Optional.of(result);
                } catch (EOFException | MismatchedInputException e) {
                    logger.warn("No snapshot found for timestamp <= {}", formattedTimestamp);
                    return Optional.empty();
                } catch (Exception e) {
                    logger.error("Error while reading snapshot from ClickHouse", e);
                    throw e;
                }
            }

        } catch (Exception e) {
            logger.error("Failed to retrieve snapshot from ClickHouse for timestamp {}", formattedTimestamp, e);
            throw new RuntimeException("Failed to retrieve snapshot", e);
        }
    }

    /**
     * Retrieves all events from ClickHouse that occurred strictly after a given
     * timestamp.
     * The events are returned in chronological order, ready for replay.
     *
     * @param timestamp The exclusive start time. Events after this point will be
     *                  fetched.
     * @return A List of DomainEvent objects, ordered by timestamp.
     */
    public List<DomainEvent> getEventsBetween(Instant startTime, Instant endTime) {
        return getEventsBetween(startTime, endTime, false);
    }

    /**
     * Reads snapshot deltas and includes rows whose domain timestamp predates the
     * snapshot but whose ClickHouse processing timestamp proves they arrived after
     * it. Ordinary forward playback must use getEventsBetween to avoid replaying
     * the already-built baseline.
     */
    public List<DomainEvent> getEventsForRecoveryBetween(Instant startTime, Instant endTime) {
        return getEventsBetween(startTime, endTime, true);
    }

    private List<DomainEvent> getEventsBetween(Instant startTime, Instant endTime, boolean includeLateEvents) {
        EventPageCursor cursor = null;
        List<DomainEvent> events = new ArrayList<>();
        do {
            EventPage page = getEventsBetweenPage(startTime, endTime, cursor, 1000, includeLateEvents);
            events.addAll(page.events());
            cursor = page.hasMore(1000) ? page.nextCursor() : null;
        } while (cursor != null);
        return events;
    }

    /**
     * Reads replay events in timestamp order without materializing the whole
     * interval. Historical simulation builds use this to keep memory bounded for
     * large restore windows.
     */
    public EventPage getEventsBetweenPage(Instant startTime, Instant endTime, EventPageCursor cursor, int pageSize) {
        return getEventsBetweenPage(startTime, endTime, cursor, pageSize, true);
    }

    private EventPage getEventsBetweenPage(Instant startTime, Instant endTime, EventPageCursor cursor, int pageSize,
            boolean includeLateEvents) {
        String formattedStartTimestamp = CLICKHOUSE_FORMATTER.format(startTime);
        String formattedEndTimestamp = CLICKHOUSE_FORMATTER.format(endTime);
        int boundedPageSize = Math.max(1, pageSize);

        String cursorFilter = "";
        Map<String, Object> queryParams = new HashMap<>();
        queryParams.put("ts_start", formattedStartTimestamp);
        queryParams.put("ts_end", formattedEndTimestamp);
        queryParams.put("limit", boundedPageSize);
        if (cursor != null) {
            cursorFilter = """
                      AND (
                        timestamp_received > {cursor_received:DateTime64(3)}
                        OR (timestamp_received = {cursor_received:DateTime64(3)}
                          AND timestamp_processed > {cursor_processed:DateTime64(3)})
                        OR (timestamp_received = {cursor_received:DateTime64(3)}
                          AND timestamp_processed = {cursor_processed:DateTime64(3)}
                          AND event_id > {cursor_event_id:UUID})
                      )
                    """;
            queryParams.put("cursor_received", CLICKHOUSE_FORMATTER.format(cursor.timestampReceived()));
            queryParams.put("cursor_processed", CLICKHOUSE_FORMATTER.format(cursor.timestampProcessed()));
            queryParams.put("cursor_event_id", cursor.eventId());
        }

        String lowerBoundFilter = includeLateEvents
                ? "AND (timestamp_received > {ts_start:DateTime64(3)} OR timestamp_processed > {ts_start:DateTime64(3)})"
                : "AND timestamp_received > {ts_start:DateTime64(3)}";
        String query = """
                SELECT timestamp_received, timestamp_processed, event_id, data
                FROM Events
                WHERE timestamp_received <= {ts_end:DateTime64(3)}
                  %s
                %s
                ORDER BY timestamp_received ASC, timestamp_processed ASC, event_id ASC
                LIMIT {limit:UInt32}
                FORMAT JSONEachRow
                SETTINGS
                    date_time_output_format = 'iso',
                    output_format_json_quote_64bit_integers = 0
                """.formatted(lowerBoundFilter, cursorFilter);

        List<DomainEvent> events = new ArrayList<>();
        EventPageCursor nextCursor = null;
        int rowCount = 0;

        try (QueryResponse response = client.query(query, queryParams).get()) {

            try (InputStream inputStream = response.getInputStream()) {
                var mapType = objectMapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class);
                MappingIterator<Map<String, Object>> it = objectMapper.readerFor(mapType).readValues(inputStream);

                while (it.hasNext()) {
                    Map<String, Object> row = it.next();
                    rowCount++;
                    Object eventData = row.get("data");
                    nextCursor = new EventPageCursor(
                            parseClickHouseInstant(row.get("timestamp_received")),
                            parseClickHouseInstant(row.get("timestamp_processed")),
                            String.valueOf(row.get("event_id")));

                    DomainEvent event = objectMapper.convertValue(eventData, DomainEvent.class);

                    if (!(event instanceof UnknownEvent)) {
                        events.add(event);
                    } else {
                        logger.warn("Deserialized UnknownEvent. Raw Data: {}", eventData);
                    }
                }
            }

            logger.debug("Retrieved {} events between {} and {}.", events.size(), formattedStartTimestamp,
                    formattedEndTimestamp);
            return new EventPage(events, nextCursor, rowCount);

        } catch (Exception e) {
            logger.error("Failed to retrieve events from ClickHouse between {} and {}", formattedStartTimestamp,
                    formattedEndTimestamp, e);
            throw new RuntimeException("Failed to retrieve events", e);
        }
    }

    public List<DomainEvent> getLatestDestinationMappingEventsBefore(Instant timestamp) {
        String query = """
                SELECT data
                FROM Events
                WHERE event_type IN ('MAP_DESTINATIONS', 'MAP_DESTINATION_EXITS')
                  AND timestamp_received <= {ts:DateTime64(3)}
                ORDER BY timestamp_received DESC, timestamp_processed DESC
                LIMIT 1 BY event_type
                FORMAT JSONEachRow
                """;
        String formattedTimestamp = CLICKHOUSE_FORMATTER.format(timestamp);
        List<DomainEvent> events = new ArrayList<>();
        try (QueryResponse response = client.query(query, Map.of("ts", formattedTimestamp)).get();
                InputStream inputStream = response.getInputStream()) {
            var mapType = objectMapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class);
            MappingIterator<Map<String, Object>> iterator = objectMapper.readerFor(mapType).readValues(inputStream);
            while (iterator.hasNext()) {
                DomainEvent event = objectMapper.convertValue(iterator.next().get("data"), DomainEvent.class);
                if (!(event instanceof UnknownEvent)) {
                    events.add(event);
                }
            }
            events.sort(java.util.Comparator.comparing(DomainEvent::getTimestamp));
            return events;
        } catch (Exception e) {
            throw new RuntimeException("Failed to retrieve destination mapping state", e);
        }
    }

    /**
     * Executes a raw SQL query and returns the result as a List of Maps.
     * Useful for ad-hoc queries like state rehydration.
     * Automatically appends 'FORMAT JSONEachRow' if not present to ensure parsing
     * works.
     *
     * @param sql The SQL query to execute.
     * @return A list of rows, where each row is a Map of column names to values.
     */
    public List<Map<String, Object>> queryForList(String sql) {
        // Ensure we request JSON format so Jackson can parse it
        String finalSql = sql.trim();
        if (!finalSql.toUpperCase().endsWith("FORMAT JSONEACHROW")) {
            finalSql += " FORMAT JSONEachRow";
        }

        logger.debug("Executing generic query: {}", finalSql);

        List<Map<String, Object>> results = new ArrayList<>();

        try (QueryResponse response = client.query(finalSql).get()) {
            try (InputStream inputStream = response.getInputStream()) {
                // Use Jackson to read the stream of JSON objects (NDJSON)
                MappingIterator<Map<String, Object>> it = objectMapper
                        .readerFor(Map.class)
                        .readValues(inputStream);

                while (it.hasNext()) {
                    results.add(it.next());
                }
            }
            return results;
        } catch (Exception e) {
            logger.error("Failed to execute generic query: {}", finalSql, e);
            throw new RuntimeException("Generic query failed", e);
        }
    }

    public CompletableFuture<List<ThroughputMetric>> getThroughputHistory(int hours) {
        Instant to = Instant.now();
        Instant from = to.minusSeconds(Math.max(1, hours) * 3600L);
        return getThroughputHistory(from, to, 5);
    }

    /**
     * Reads throughput buckets written after successful event reductions.
     * Entered and exited values are summed when the requested bucket is larger
     * than the stored interval; current item count follows the latest snapshot in
     * each output bucket.
     */
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

    /**
     * Aggregates completed journeys by exit time and attaches recirculation facts
     * that occurred between each journey's selected creation and successful exit.
     */
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

    /**
     * Reads immutable live conveyor state events through the requested query end.
     * Processing from creation rather than only from the window start lets the stop
     * state machine clip intervals without losing an already-open stop.
     */
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

    /**
     * Removes queued and persisted facts for a destroyed simulation. Synchronous
     * mutations ensure an immediately reused id cannot observe the old analytics.
     */
    public void deleteOperationalAnalyticsForSimulation(String simulationId) {
        exitCandidateQueue.removeIf(candidate -> simulationId.equals(candidate.simulationId()));
        recirculationQueue.removeIf(fact -> simulationId.equals(fact.simulationId()));
        simulationConnectionQueue.removeIf(signal -> simulationId.equals(signal.simulationId()));
        for (String table : List.of(
                "analytics_exit_candidates",
                "analytics_completed_journeys",
                "analytics_recirculation_facts",
                "analytics_simulation_connection_events",
                "analytics_alarm_events",
                "analytics_alarm_affected_items")) {
            String sql = "ALTER TABLE " + table
                    + " DELETE WHERE simulation_id = {simulation_id:String} SETTINGS mutations_sync = 1";
            try (QueryResponse ignored = client.query(sql, Map.of("simulation_id", simulationId)).get()) {
                // Mutation completion is enforced by mutations_sync.
            } catch (Exception e) {
                throw new IllegalStateException("Failed to delete simulation analytics from " + table, e);
            }
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

    private boolean asBoolean(Object value) {
        return value instanceof Boolean bool ? bool : Integer.parseInt(String.valueOf(value)) != 0;
    }

    private Instant parseClickHouseInstant(Object value) {
        if (value instanceof String text) {
            try {
                return Instant.parse(text);
            } catch (Exception ignored) {
                LocalDateTime localDateTime = LocalDateTime.parse(text, CLICKHOUSE_FORMATTER);
                return localDateTime.toInstant(ZoneOffset.UTC);
            }
        }
        throw new IllegalArgumentException("Unsupported ClickHouse timestamp value: " + value);
    }

    private Instant parseNullableInstant(Object value) {
        if (value == null || "null".equalsIgnoreCase(String.valueOf(value))) {
            return null;
        }
        return parseClickHouseInstant(value);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asPayload(Object value) {
        if (value instanceof Map<?, ?> map) {
            return new LinkedHashMap<>((Map<String, Object>) map);
        }
        if (value == null) {
            return Map.of();
        }
        return Map.of("value", value);
    }

    private long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    private double asDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return Double.parseDouble(String.valueOf(value));
    }

    private Double asNullableDouble(Object value) {
        return value == null ? null : asDouble(value);
    }

    private Boolean asNullableBoolean(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        return Boolean.parseBoolean(String.valueOf(value));
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

    private final BlockingQueue<MetricEvent> metricQueue = new LinkedBlockingQueue<>(MAX_QUEUE_SIZE);

    public synchronized void saveMetricAsync(MetricEvent metric) {
        enqueueOrThrow(metricQueue, metric, "component metric");
        // If queue gets too big, force flush immediately
        if (metricQueue.size() >= BATCH_SIZE) {
            flushMetrics();
        }
    }

    @Scheduled(fixedRate = 1000) // Flush every 1 second
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

    /**
     * Batch inserts metrics into the ComponentMetrics table.
     * Uses JSONEachRow format for type safety and efficiency.
     * 
     * @param metrics A list of maps, where each map represents a row (keys:
     *                timestamp, simulation_id, etc.)
     */
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

    /**
     * Executes a raw SQL statement (INSERT, UPDATE, ALTER, etc.) without returning
     * results.
     * 
     * @param sql The SQL string to execute.
     */
    public void execute(String sql) {
        logger.debug("Executing raw SQL: {}", sql);
        try {
            // .get() waits for the future to complete, ensuring the query ran
            client.query(sql).get().close();
        } catch (Exception e) {
            logger.error("Failed to execute SQL: {}", sql, e);
            throw new RuntimeException("Raw SQL execution failed", e);
        }
    }

    private <T> void enqueueOrThrow(BlockingQueue<T> queue, T value, String queueName) {
        if (!queue.offer(value)) {
            throw new IllegalStateException("ClickHouse " + queueName + " queue reached capacity " + MAX_QUEUE_SIZE);
        }
    }
}
