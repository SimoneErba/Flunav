package com.flunav.backend.services;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.query.QueryResponse;
import com.clickhouse.data.ClickHouseFormat;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.flunav.backend.models.graph.GraphData;

import flunav.events.DomainEvent;
import flunav.events.EntityEvent;
import flunav.events.PathTraversedEvent;
import flunav.events.UnknownEvent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingDeque;

import com.flunav.backend.services.ClickHouseService.*;
/** Owns the domain history queue and snapshot/replay storage; never appends simulation replay as live history. */
final class ClickHouseEventStorage extends ClickHouseAccess {
    private static final Logger logger = LoggerFactory.getLogger(ClickHouseEventStorage.class);
    private final ClickHouseAnalyticsStorage analytics;
    private final LinkedBlockingDeque<DomainEvent> eventQueue = new LinkedBlockingDeque<>(MAX_QUEUE_SIZE);

    ClickHouseEventStorage(Client client, ObjectMapper mapper, String database, ClickHouseAnalyticsStorage analytics) {
        super(client, mapper, database);
        this.analytics = analytics;
    }

    public synchronized void saveEventAsync(DomainEvent event) {
        if (event instanceof PathTraversedEvent pathTraversedEvent) {
            analytics.savePathTraversalMetricAsync(pathTraversedEvent);
            return;
        }

        enqueueOrThrow(eventQueue, event, "event");

        if (eventQueue.size() >= BATCH_SIZE) {
            flushEvents();
        }
    }

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

    /** Checks event IDs before insertion so an ambiguous timeout retry cannot append duplicate physical history. */
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

    /** Drains history before a snapshot is accepted behind EventProcessor's live snapshot barrier. */
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

    public List<DomainEvent> getEventsBetween(Instant startTime, Instant endTime) {
        return getEventsBetween(startTime, endTime, false);
    }

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

    /** Reads replay pages with the existing timestamp and event-ID ordering; no simulation history is appended. */
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

    public List<DomainEvent> getLatestConfigurationEventsBefore(Instant timestamp) {
        String query = """
                SELECT data
                FROM Events
                WHERE event_type IN ('MAP_DESTINATIONS', 'MAP_DESTINATION_EXITS', 'MAP_SENSOR_MAPPINGS')
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
            throw new RuntimeException("Failed to retrieve configuration state", e);
        }
    }
}
