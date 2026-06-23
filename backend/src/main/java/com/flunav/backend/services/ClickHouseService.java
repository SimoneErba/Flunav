package com.flunav.backend.services;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.query.QueryResponse;
import com.clickhouse.data.ClickHouseFormat;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.flunav.backend.models.analytics.EntityEventType;
import com.flunav.backend.models.analytics.MetricEvent;
import com.flunav.backend.models.graph.GraphData;
import com.flunav.backend.models.response.BadActorMetric;
import com.flunav.backend.models.response.EntityEventRecord;
import com.flunav.backend.models.response.ThroughputMetric;

import flunav.events.DomainEvent;
import flunav.events.EntityEvent;
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
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.LinkedBlockingQueue;

@Service
public class ClickHouseService {
    private static final Logger logger = LoggerFactory.getLogger(ClickHouseService.class);
    private final Client client;
    private final ObjectMapper objectMapper;
    private static final DateTimeFormatter CLICKHOUSE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
            .withZone(ZoneOffset.UTC);
    private final LinkedBlockingDeque<DomainEvent> eventQueue = new LinkedBlockingDeque<>();
    private static final int BATCH_SIZE = 1000;

    public ClickHouseService(
            @Value("${clickhouse.url}") String clickhouseUrl,
            @Value("${clickhouse.username}") String username,
            @Value("${clickhouse.password}") String password,
            ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        try {
            this.client = new Client.Builder()
                    .addEndpoint(clickhouseUrl)
                    .setUsername(username)
                    .setPassword(password)
                    .build();
            logger.info("ClickHouse Client V2 initialized successfully.");
        } catch (Exception e) {
            logger.error("Failed to initialize ClickHouse client", e);
            throw new RuntimeException("ClickHouse init error", e);
        }
    }

    @PreDestroy
    public void cleanup() {
        flushEvents();
        if (client != null) {
            client.close();
        }
    }

    public void saveEventAsync(DomainEvent event) {
        eventQueue.offer(event);

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
            StringBuilder jsonBatch = new StringBuilder();

            for (DomainEvent event : batch) {
                Map<String, Object> clickHouseRow = new HashMap<>();
                Instant processedTimestamp = Instant.now();

                clickHouseRow.put("event_type", event.getEventType());
                clickHouseRow.put("event_id", event.getEventId());
                clickHouseRow.put("data", event);
                clickHouseRow.put("timestamp_received", CLICKHOUSE_FORMATTER.format(event.getTimestamp()));
                clickHouseRow.put("timestamp_processed", CLICKHOUSE_FORMATTER.format(processedTimestamp));

                if (event instanceof EntityEvent) {
                    clickHouseRow.put("entity_id", ((EntityEvent) event).getEntityId());
                } else {
                    clickHouseRow.put("entity_id", null);
                }

                // Append JSON line
                jsonBatch.append(objectMapper.writeValueAsString(clickHouseRow)).append("\n");
            }

            // Perform a SINGLE insert for the whole batch
            try (var inputStream = new ByteArrayInputStream(jsonBatch.toString().getBytes(StandardCharsets.UTF_8))) {
                client.insert("Events", inputStream, ClickHouseFormat.JSONEachRow).get();
            }

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
        EventPageCursor cursor = null;
        List<DomainEvent> events = new ArrayList<>();
        do {
            EventPage page = getEventsBetweenPage(startTime, endTime, cursor, 1000);
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

        String query = """
                SELECT timestamp_received, timestamp_processed, event_id, data
                FROM Events
                WHERE timestamp_received > {ts_start:DateTime64(3)}
                  AND timestamp_received <= {ts_end:DateTime64(3)}
                %s
                ORDER BY timestamp_received ASC, timestamp_processed ASC, event_id ASC
                LIMIT {limit:UInt32}
                FORMAT JSONEachRow
                SETTINGS
                    date_time_output_format = 'iso',
                    output_format_json_quote_64bit_integers = 0
                """.formatted(cursorFilter);

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
                    FROM default.analytics_time_series
                    WHERE bucket_start >= {from:DateTime64(3)}
                      AND bucket_start <= {to:DateTime64(3)}
                    GROUP BY ts
                    ORDER BY ts ASC
                    FORMAT JSONEachRow
                    SETTINGS
                        date_time_output_format = 'iso',
                        output_format_json_quote_64bit_integers = 0
                """.formatted(normalizedBucketSeconds);

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
                return new ArrayList<>();
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

    public CompletableFuture<List<BadActorMetric>> getTopActiveComponents(int limit) {
        String sql = String.format("""
                    SELECT
                        location_id,
                        total_items_passed,
                        formatDateTime(last_activity, '%%Y-%%m-%%dT%%H:%%M:%%S') as last_activity
                    FROM default.analytics_components
                    ORDER BY total_items_passed DESC
                    LIMIT %d
                    FORMAT JSONEachRow
                """, limit);

        return CompletableFuture.supplyAsync(() -> {
            List<BadActorMetric> metrics = new ArrayList<>();
            try (QueryResponse response = client.query(sql).get()) {
                try (InputStream inputStream = response.getInputStream()) {
                    MappingIterator<Map<String, Object>> it = objectMapper
                            .readerFor(Map.class)
                            .readValues(inputStream);

                    while (it.hasNext()) {
                        Map<String, Object> row = it.next();
                        metrics.add(new BadActorMetric(
                                (String) row.get("location_id"),
                                (String) row.get("location_id"),
                                ((Number) row.get("total_items_passed")).longValue(),
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

    private final BlockingQueue<MetricEvent> metricQueue = new LinkedBlockingQueue<>();

    public void saveMetricAsync(MetricEvent metric) {
        metricQueue.offer(metric);
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

        saveMetricSnapshots(rows); // Reuse existing bulk insert method
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
}
