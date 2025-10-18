package com.flumen.backend.services;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.query.QueryResponse;
import com.clickhouse.data.ClickHouseFormat;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.flumen.backend.models.graph.GraphData;

import flumen.events.DomainEvent;
import flumen.events.EntityEvent;
import flumen.events.UnknownEvent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PreDestroy;
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
import java.util.concurrent.CompletableFuture;

@Service
public class ClickHouseService {
    private static final Logger logger = LoggerFactory.getLogger(ClickHouseService.class);

    private final Client client;
    private final ObjectMapper objectMapper;
    private static final DateTimeFormatter CLICKHOUSE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneOffset.UTC);
    public ClickHouseService(
            @Value("${clickhouse.url}") String clickhouseUrl,
            @Value("${clickhouse.username}") String username,
            @Value("${clickhouse.password}") String password,
            ObjectMapper objectMapper
    ) {
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
        if (client != null) {
            client.close();
        }
    }

    public void saveEvent(DomainEvent event) {
        try {
            Map<String, Object> clickHouseRow = new HashMap<>();
            Instant processedTimestamp = Instant.now();
            String formattedEventTimestamp = CLICKHOUSE_FORMATTER.format(event.getTimestamp());
            String formattedProcessedTimestamp = CLICKHOUSE_FORMATTER.format(processedTimestamp);

            clickHouseRow.put("event_type", event.getEventType());
            clickHouseRow.put("event_id", event.getEventId());
            clickHouseRow.put("data", event);
            clickHouseRow.put("timestamp_received", formattedEventTimestamp);
            clickHouseRow.put("timestamp_processed", formattedProcessedTimestamp);

            if (event instanceof EntityEvent) {
                EntityEvent entityEvent = (EntityEvent) event;
                clickHouseRow.put("entity_id", entityEvent.getEntityId());
            } else {
                clickHouseRow.put("entity_id", null);
            }

            String finalJson = objectMapper.writeValueAsString(clickHouseRow);

            try (var inputStream = new ByteArrayInputStream(finalJson.getBytes(StandardCharsets.UTF_8))) {
                client.insert("Events", inputStream, ClickHouseFormat.JSONEachRow);
            }

            logger.debug("Event saved: {}", event.getEventId());

        } catch (Exception e) {
            logger.error("Error saving event {} to ClickHouse", event.getEventId(), e);
            throw new RuntimeException("Save failed", e);
        }
    }

    public void saveEventAsync(DomainEvent event) {
        CompletableFuture.runAsync(() -> saveEvent(event))
                .exceptionally(ex -> {
                    logger.error("Async error saving event {}", event.getEventId(), ex);
                    return null;
                });
    }

    /**
     * Saves a graph snapshot to the 'snapshots' table in ClickHouse.
     * @param snapshotId The unique ID for the snapshot.
     * @param timestamp The time the snapshot was taken.
     * @param graphData The graph data object to be serialized and stored.
     */
    public void saveSnapshot(String snapshotId, Instant timestamp, GraphData graphData) {
        try {
            // Create a Map that directly matches the 'snapshots' table columns.
            Map<String, Object> clickHouseRow = new HashMap<>();

            clickHouseRow.put("snapshot_id", snapshotId);
            clickHouseRow.put("timestamp", CLICKHOUSE_FORMATTER.format(timestamp));
            
            // Let Jackson serialize the rich GraphData object into a nested JSON object for the 'graph_data' column.
            clickHouseRow.put("graph_data", graphData);

            // Serialize the entire row map into a single JSON string for insertion.
            String finalJson = objectMapper.writeValueAsString(clickHouseRow);

            // Insert into the 'snapshots' table using the JSONEachRow format.
            try (var inputStream = new ByteArrayInputStream(finalJson.getBytes(StandardCharsets.UTF_8))) {
                client.insert("snapshots", inputStream, ClickHouseFormat.JSONEachRow);
            }

            logger.info("Successfully saved graph snapshot with ID: {}", snapshotId);

        } catch (Exception e) {
            logger.error("Error saving snapshot {} to ClickHouse", snapshotId, e);
            throw new RuntimeException("Snapshot save failed", e);
        }
    }

    public record Snapshot(GraphData graphData, Instant timestamp) {}

    /**
     * Retrieves the most recent graph snapshot from ClickHouse at or before a given point in time.
     * The method returns a Snapshot object containing both the graph data and its timestamp.
     *
     * @param timestamp The point in time to find the latest snapshot for.
     * @return An Optional containing the Snapshot if one is found, otherwise an empty Optional.
     */
    public Optional<Snapshot> getMostRecentSnapshotBefore(Instant timestamp) {
        String formattedTimestamp = CLICKHOUSE_FORMATTER.format(timestamp);
        String query = "SELECT graph_data, timestamp FROM snapshots WHERE timestamp <= {ts:Datetime64(3)} ORDER BY timestamp DESC LIMIT 1 FORMAT JSONEachRow";

        logger.debug("Executing query to find most recent snapshot before {}", formattedTimestamp);

        try (QueryResponse response = client.query(query, Map.of("ts", formattedTimestamp)).get()) {
        

            try (InputStream inputStream = response.getInputStream()) {

                var mapType = objectMapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class);
                
                try{
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
     * Retrieves all events from ClickHouse that occurred strictly after a given timestamp.
     * The events are returned in chronological order, ready for replay.
     *
     * @param timestamp The exclusive start time. Events after this point will be fetched.
     * @return A List of DomainEvent objects, ordered by timestamp.
     */
    public List<DomainEvent> getEventsBetween(Instant startTime, Instant endTime) {
        String formattedStartTimestamp = CLICKHOUSE_FORMATTER.format(startTime);
        String formattedEndTimestamp = CLICKHOUSE_FORMATTER.format(endTime);
    
        String query = "SELECT data FROM Events WHERE timestamp_processed >= {ts_start:Datetime64(3)} AND timestamp_processed < {ts_end:Datetime64(3)} ORDER BY timestamp_processed ASC FORMAT JSONEachRow";
        
        logger.info("Executing query to find events between {} and {}", formattedStartTimestamp, formattedEndTimestamp);
        
        List<DomainEvent> events = new ArrayList<>();
    
        try (QueryResponse response = client.query(query, Map.of("ts_start", formattedStartTimestamp, "ts_end", formattedEndTimestamp)).get()) {
    
            try (InputStream inputStream = response.getInputStream()) {                
                var mapType = objectMapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class);
                MappingIterator<Map<String, Object>> it = objectMapper.readerFor(mapType).readValues(inputStream);

                while (it.hasNext()) {
                    Map<String, Object> row = it.next();
                    Object eventData = row.get("data");
    
                    DomainEvent event = objectMapper.convertValue(eventData, DomainEvent.class);

                    if(!(event instanceof UnknownEvent)){
                        events.add(event);
                    }
                }
            }
    
            logger.info("Successfully retrieved {} events between the specified timestamps.", events.size());
            return events;
    
        } catch (Exception e) {
            logger.error("Failed to retrieve events from ClickHouse between {} and {}", formattedStartTimestamp, formattedEndTimestamp, e);
            throw new RuntimeException("Failed to retrieve events", e);
        }
    }
}