package com.flunav.backend.services;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.query.QueryResponse;
import com.clickhouse.data.ClickHouseFormat;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.flunav.backend.models.analytics.MetricEvent;
import com.flunav.backend.models.analytics.ThroughputDto;
import com.flunav.backend.models.graph.GraphData;
import com.flunav.backend.models.response.BadActorMetric;
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
    private static final DateTimeFormatter CH_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneOffset.UTC);
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

            logger.info("Flushed {} events to ClickHouse", batch.size());

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

            // Let Jackson serialize the rich GraphData object into a nested JSON object for
            // the 'graph_data' column.
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
        String query = "SELECT graph_data, timestamp FROM snapshots WHERE timestamp <= {ts:Datetime64(3)} ORDER BY timestamp DESC LIMIT 1 FORMAT JSONEachRow";

        logger.debug("Executing query to find most recent snapshot before {}", formattedTimestamp);

        try (QueryResponse response = client.query(query, Map.of("ts", formattedTimestamp)).get()) {

            try (InputStream inputStream = response.getInputStream()) {

                var mapType = objectMapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class);

                try {
                    Map<String, Object> row = objectMapper.readValue(inputStream, mapType);

                    GraphData graphData = objectMapper.convertValue(row.get("graph_data"), GraphData.class);
                    String timestampString = (String) row.get("timestamp");
                    LocalDateTime localDateTime = LocalDateTime.parse(timestampString, CLICKHOUSE_FORMATTER);
                    Instant snapshotTimestamp = localDateTime.toInstant(ZoneOffset.UTC);

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
        String formattedStartTimestamp = CLICKHOUSE_FORMATTER.format(startTime);
        String formattedEndTimestamp = CLICKHOUSE_FORMATTER.format(endTime);

        String query = "SELECT data FROM Events WHERE timestamp_received > {ts_start:Datetime64(3)} AND timestamp_received <= {ts_end:Datetime64(3)} ORDER BY timestamp_received ASC, timestamp_processed ASC FORMAT JSONEachRow";

        logger.info("Executing query to find events between {} and {}", formattedStartTimestamp, formattedEndTimestamp);

        List<DomainEvent> events = new ArrayList<>();

        try (QueryResponse response = client
                .query(query, Map.of("ts_start", formattedStartTimestamp, "ts_end", formattedEndTimestamp)).get()) {

            try (InputStream inputStream = response.getInputStream()) {
                var mapType = objectMapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class);
                MappingIterator<Map<String, Object>> it = objectMapper.readerFor(mapType).readValues(inputStream);

                while (it.hasNext()) {
                    Map<String, Object> row = it.next();
                    Object eventData = row.get("data");

                    DomainEvent event = objectMapper.convertValue(eventData, DomainEvent.class);

                    if (!(event instanceof UnknownEvent)) {
                        events.add(event);
                    } else {
                        logger.warn("Deserialized UnknownEvent. Raw Data: {}", eventData);
                    }
                }
            }

            logger.info("Successfully retrieved {} events between the specified timestamps.", events.size());
            return events;

        } catch (Exception e) {
            logger.error("Failed to retrieve events from ClickHouse between {} and {}", formattedStartTimestamp,
                    formattedEndTimestamp, e);
            throw new RuntimeException("Failed to retrieve events", e);
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
        // Note: We use FORMAT JSONEachRow to make parsing easy with Jackson
        String sql = String.format("""
                    SELECT
                        minute as ts,
                        sum(items_entered) as entered,
                        sum(items_exited) as exited,
                        sum(movements_count) as segments
                    FROM default.analytics_time_series
                    WHERE minute >= now() - INTERVAL %d HOUR
                    GROUP BY minute
                    ORDER BY minute ASC
                    FORMAT JSONEachRow
                """, hours);

        return CompletableFuture.supplyAsync(() -> {
            List<ThroughputMetric> metrics = new ArrayList<>();
            try (QueryResponse response = client.query(sql).get()) {
                try (InputStream inputStream = response.getInputStream()) {
                    // Use Jackson to read the stream of JSON objects
                    MappingIterator<Map<String, Object>> it = objectMapper
                            .readerFor(Map.class)
                            .readValues(inputStream);

                    while (it.hasNext()) {
                        Map<String, Object> row = it.next();
                        String tsString = (String) row.get("ts");

                        LocalDateTime localDateTime = LocalDateTime.parse(tsString, CH_DATE_FORMATTER);
                        Instant ts = localDateTime.toInstant(ZoneOffset.UTC);

                        metrics.add(new ThroughputMetric(
                                ts,
                                ((Number) row.get("entered")).longValue(),
                                ((Number) row.get("exited")).longValue(),
                                ((Number) row.get("segments")).longValue()));
                    }
                }
            } catch (Exception e) {
                logger.error("Failed to fetch throughput history", e);
                return new ArrayList<>();
            }
            return metrics;
        });
    }

    public ThroughputMetric getLatestThroughput() {
        String sql = """
                SELECT
                    minute as ts,
                    sum(items_entered) as entered,
                    sum(items_exited) as exited,
                    sum(movements_count) as segments
                FROM default.analytics_time_series
                WHERE minute >= toStartOfMinute(now())
                GROUP BY minute
                FORMAT JSONEachRow
                """;

        try (QueryResponse response = client.query(sql).get();
                InputStream inputStream = response.getInputStream()) {

            MappingIterator<ThroughputDto> it = objectMapper.readerFor(ThroughputDto.class)
                    .readValues(inputStream);

            if (it.hasNext()) {
                ThroughputDto row = it.next();

                LocalDateTime localDateTime = LocalDateTime.parse(row.ts, CH_DATE_FORMATTER);

                Instant ts = localDateTime.toInstant(ZoneOffset.UTC);

                return new ThroughputMetric(
                        ts,
                        row.entered,
                        row.exited,
                        row.segments);
            }

        } catch (Exception e) {
            logger.error("Failed to fetch latest throughput", e);
        }

        return new ThroughputMetric(Instant.now(), 0, 0, 0);
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
