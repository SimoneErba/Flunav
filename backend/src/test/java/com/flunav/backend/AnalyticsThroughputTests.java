package com.flunav.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flunav.backend.context.DatabaseContextHolder;
import com.flunav.backend.models.input.LocationInput;
import com.flunav.backend.models.response.ThroughputMetric;
import com.flunav.backend.repositories.LiveItemRepository;
import com.flunav.backend.repositories.LiveLocationRepository;
import com.flunav.backend.repositories.LiveSimulationRepository;
import com.flunav.backend.services.ClickHouseService;
import com.flunav.backend.services.EventProcessor;
import com.flunav.backend.services.LocationService;
import com.flunav.backend.services.LiveSystemScheduler;
import com.flunav.backend.services.OrientDBService;
import com.flunav.backend.services.ThroughputBucketService;

import flunav.events.ChuteEmptyEvent;
import flunav.events.ItemCreatedEvent;
import flunav.events.ItemDeletedEvent;
import flunav.types.LocationType;
import flunav.types.PositionType;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.support.AbstractMessageChannel;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.test.context.TestConstructor;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "springwolf.enabled=false",
        "app.demo-mode=false",
        "stale-item-cleanup.enabled=false",
        "state-recovery.enabled=false",
        "graph-snapshot.enabled=false"
})
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class AnalyticsThroughputTests extends BaseIntegrationTest {

    private static final String SIMULATION_ID = "analytics-throughput-sim";

    private final ClickHouseService clickHouseService;
    private final EventProcessor eventProcessor;
    private final ThroughputBucketService throughputBucketService;
    private final LocationService locationService;
    private final OrientDBService orientDBService;
    private final LiveItemRepository liveItemRepository;
    private final LiveLocationRepository liveLocationRepository;
    private final LiveSimulationRepository liveSimulationRepository;
    private final LiveSystemScheduler liveSystemScheduler;
    private final StringRedisTemplate redisTemplate;
    private final AbstractMessageChannel brokerChannel;
    private final int serverPort;
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    AnalyticsThroughputTests(
            ClickHouseService clickHouseService,
            EventProcessor eventProcessor,
            ThroughputBucketService throughputBucketService,
            LocationService locationService,
            OrientDBService orientDBService,
            LiveItemRepository liveItemRepository,
            LiveLocationRepository liveLocationRepository,
            LiveSimulationRepository liveSimulationRepository,
            LiveSystemScheduler liveSystemScheduler,
            StringRedisTemplate redisTemplate,
            @org.springframework.beans.factory.annotation.Qualifier("brokerChannel")
            AbstractMessageChannel brokerChannel,
            @LocalServerPort int serverPort) {
        this.clickHouseService = clickHouseService;
        this.eventProcessor = eventProcessor;
        this.throughputBucketService = throughputBucketService;
        this.locationService = locationService;
        this.orientDBService = orientDBService;
        this.liveItemRepository = liveItemRepository;
        this.liveLocationRepository = liveLocationRepository;
        this.liveSimulationRepository = liveSimulationRepository;
        this.liveSystemScheduler = liveSystemScheduler;
        this.redisTemplate = redisTemplate;
        this.brokerChannel = brokerChannel;
        this.serverPort = serverPort;
    }

    @BeforeEach
    void setup() throws Exception {
        resetState();
        truncateAnalyticsTables();
    }

    @AfterEach
    void tearDown() throws Exception {
        liveSystemScheduler.cancelAll();
        truncateAnalyticsTables();
        resetState();
    }

    @Test
    void itemCreatedIncrementsEnteredAndCurrentCount() {
        Instant timestamp = Instant.now();
        createLocation("analytics-create-start", LocationType.GENERIC, 100);

        eventProcessor.processEvent(new ItemCreatedEvent("analytics-created-item", "Created", 1.0, true,
                "analytics-create-start", PositionType.LOCATION, 0.0, Map.of(), timestamp));
        throughputBucketService.flushBuckets();

        ThroughputMetric metric = metricForBucket(timestamp);
        assertEquals(1, metric.getItemsEntered());
        assertEquals(0, metric.getItemsExited());
        assertEquals(1, metric.getItemsCurrent());
        assertEquals(ThroughputBucketService.BUCKET_SECONDS, metric.getBucketSeconds());
    }

    @Test
    void itemDeletedIncrementsExitedAndLowersCurrentCount() {
        createLocation("analytics-delete-start", LocationType.GENERIC, 100);
        liveItemRepository.saveItemState("analytics-deleted-item", "analytics-delete-start", PositionType.LOCATION,
                Instant.now(), 0.0, "Deleted", List.of(), null, null);
        assertEquals(1, liveItemRepository.countActiveItems());

        ItemDeletedEvent deleteEvent = new ItemDeletedEvent("analytics-deleted-item");
        liveItemRepository.deleteItem("analytics-deleted-item");
        throughputBucketService.recordSuccessfulReduction(deleteEvent, Map.of("status", "PROCESSED_SUCCESSFULLY"), 0);
        throughputBucketService.flushBuckets();

        ThroughputMetric metric = metricForBucket(deleteEvent.getTimestamp());
        assertEquals(0, metric.getItemsEntered());
        assertEquals(1, metric.getItemsExited());
        assertEquals(0, metric.getItemsCurrent());
    }

    @Test
    void chuteEmptyIncrementsExitedByRemovedOccupants() {
        Instant now = Instant.now();
        createLocation("analytics-chute", LocationType.CHUTE, 10);
        for (int index = 0; index < 3; index++) {
            eventProcessor.processEventWithoutBroadcast(new ItemCreatedEvent("analytics-chute-item-" + index,
                    "Chute Item " + index, 1.0, true, "analytics-chute", PositionType.LOCATION, 0.0, Map.of(),
                    now.plusMillis(index)));
        }
        assertEquals(3, liveLocationRepository.getItemCount("analytics-chute"));
        assertEquals(3, liveItemRepository.countActiveItems());

        ChuteEmptyEvent chuteEmptyEvent = new ChuteEmptyEvent("analytics-chute", now);
        eventProcessor.processEvent(chuteEmptyEvent);
        throughputBucketService.flushBuckets();

        ThroughputMetric metric = metricForBucket(chuteEmptyEvent.getTimestamp());
        assertEquals(0, metric.getItemsEntered());
        assertEquals(3, metric.getItemsExited());
        assertEquals(0, metric.getItemsCurrent());
    }

    @Test
    void simulationBucketsAreNotPersistedToClickHouse() {
        Instant timestamp = Instant.now();
        orientDBService.createInMemoryDatabase(SIMULATION_ID);

        try (var ignored = DatabaseContextHolder.enterSimulationContext(SIMULATION_ID)) {
            createLocation("analytics-sim-start", LocationType.GENERIC, 100);
            eventProcessor.processEvent(new ItemCreatedEvent("analytics-sim-item", "Sim Item", 1.0, true,
                    "analytics-sim-start", PositionType.LOCATION, 0.0, Map.of(), timestamp));
        }
        throughputBucketService.flushBuckets();

        List<ThroughputMetric> metrics = clickHouseService.getThroughputHistory(1).join();
        assertTrue(metrics.isEmpty(), "Simulation throughput must not be written to live ClickHouse history");
    }

    @Test
    void futureSimulationBucketFlushesAtSimulationProgressAndKeepsReductionCurrentCount() {
        Instant eventTimestamp = Instant.now().plus(Duration.ofHours(1));
        saveSimulationState(eventTimestamp.plusSeconds(ThroughputBucketService.BUCKET_SECONDS));
        orientDBService.createInMemoryDatabase(SIMULATION_ID);

        List<Message<?>> messages = captureBrokerMessages(() -> {
            try (var ignored = DatabaseContextHolder.enterSimulationContext(SIMULATION_ID)) {
                createLocation("analytics-future-start", LocationType.GENERIC, 100);
                eventProcessor.processEvent(new ItemCreatedEvent("analytics-future-item", "Future Item", 1.0, true,
                        "analytics-future-start", PositionType.LOCATION, 0.0, Map.of(), eventTimestamp));
                liveItemRepository.saveItemState("analytics-unrecorded-item", "analytics-future-start",
                        PositionType.LOCATION, eventTimestamp, 0.0, "Unrecorded", List.of(), null, null);
            }
            throughputBucketService.flushCompletedBuckets();
        });

        JsonNode metric = throughputMetricFrom(messages, SIMULATION_ID);
        assertEquals(1, metric.path("itemsEntered").asLong());
        assertEquals(1, metric.path("itemsCurrent").asLong(),
                "Current count must describe state at the last reduction in the bucket");
    }

    @Test
    void pastSimulationBucketFlushesToSimulationWebSocketTopic() {
        Instant eventTimestamp = Instant.now().minus(Duration.ofHours(1));
        saveSimulationState(eventTimestamp.plusSeconds(ThroughputBucketService.BUCKET_SECONDS));
        orientDBService.createInMemoryDatabase(SIMULATION_ID);

        List<Message<?>> messages = captureBrokerMessages(() -> {
            try (var ignored = DatabaseContextHolder.enterSimulationContext(SIMULATION_ID)) {
                createLocation("analytics-past-start", LocationType.GENERIC, 100);
                eventProcessor.processEvent(new ItemCreatedEvent("analytics-past-item", "Past Item", 1.0, true,
                        "analytics-past-start", PositionType.LOCATION, 0.0, Map.of(), eventTimestamp));
            }
            throughputBucketService.flushCompletedBuckets();
        });

        JsonNode metric = throughputMetricFrom(messages, SIMULATION_ID);
        assertEquals(bucketStart(eventTimestamp).toString(), metric.path("timestamp").asText());
    }

    @Test
    void analyticsSchemaMigrationArchivesOldTableAndCreatesBucketSchema() throws Exception {
        int legacyTablesBefore;
        try (Connection connection = clickHouseConnection();
                Statement statement = connection.createStatement()) {
            legacyTablesBefore = countLegacyAnalyticsTables(statement);
            statement.execute("DROP TABLE analytics_time_series");
            statement.execute("""
                    CREATE TABLE analytics_time_series
                    (
                        minute DateTime,
                        items_entered UInt32,
                        items_exited UInt32,
                        movements_count UInt32
                    )
                    ENGINE = SummingMergeTree()
                    ORDER BY minute
                    """);
            statement.execute("INSERT INTO analytics_time_series VALUES (now(), 1, 2, 3)");
        }

        var result = CLICKHOUSE_CONTAINER.execInContainer(
                "bash", "/docker-entrypoint-initdb.d/003_analytics_count.sh");
        assertEquals(0, result.getExitCode(), result.getStderr());

        try (Connection connection = clickHouseConnection();
                Statement statement = connection.createStatement()) {
            assertEquals(legacyTablesBefore + 1, countLegacyAnalyticsTables(statement));
            try (ResultSet columns = statement.executeQuery("""
                    SELECT name, type
                    FROM system.columns
                    WHERE database = 'default' AND table = 'analytics_time_series'
                    ORDER BY position
                    """)) {
                List<String> schema = new ArrayList<>();
                while (columns.next()) {
                    schema.add(columns.getString("name") + ":" + columns.getString("type"));
                }
                assertEquals(List.of(
                        "bucket_start:DateTime64(3)",
                        "bucket_seconds:UInt16",
                        "items_entered:UInt64",
                        "items_exited:UInt64",
                        "items_current:UInt64"), schema);
            }
        }
    }

    @Test
    void throughputHistoryEndpointReturnsBucketValues() throws Exception {
        Instant timestamp = Instant.now();
        createLocation("analytics-endpoint-start", LocationType.GENERIC, 100);
        eventProcessor.processEvent(new ItemCreatedEvent("analytics-endpoint-item", "Endpoint", 1.0, true,
                "analytics-endpoint-start", PositionType.LOCATION, 0.0, Map.of(), timestamp));
        throughputBucketService.flushBuckets();

        HttpResponse<String> response = httpClient.send(
                HttpRequest.newBuilder(URI.create(baseUrl() + "/api/analytics/throughput/history?hours=1&bucketSeconds=5"))
                        .header("Authorization", "Bearer " + loginToken())
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        JsonNode metric = findMetric(objectMapper.readTree(response.body()), bucketStart(timestamp).toString());
        assertEquals(1, metric.get("itemsEntered").asLong());
        assertEquals(0, metric.get("itemsExited").asLong());
        assertEquals(1, metric.get("itemsCurrent").asLong());
        assertEquals(5, metric.get("bucketSeconds").asInt());
        assertFalse(metric.hasNonNull("segmentsProcessed"));
    }

    private ThroughputMetric metricForBucket(Instant timestamp) {
        Instant bucketStart = bucketStart(timestamp);
        return clickHouseService.getThroughputHistory(1).join().stream()
                .filter(metric -> bucketStart.equals(metric.getTimestamp()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing metric for bucket " + bucketStart));
    }

    private Instant bucketStart(Instant timestamp) {
        long bucketMillis = ThroughputBucketService.BUCKET_SECONDS * 1000L;
        long epochMillis = timestamp.toEpochMilli();
        return Instant.ofEpochMilli((epochMillis / bucketMillis) * bucketMillis);
    }

    private void createLocation(String id, LocationType type, int capacity) {
        locationService.createLocation(new LocationInput(id, id, 0.0, 0.0, null, null, type, capacity,
                true, false, Map.of()));
    }

    private void truncateAnalyticsTables() throws Exception {
        throughputBucketService.flushBuckets();
        clickHouseService.flushEvents();
        try (Connection connection = clickHouseConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("TRUNCATE TABLE Events");
            statement.execute("TRUNCATE TABLE analytics_time_series");
        }
    }

    private void saveSimulationState(Instant progress) {
        liveSimulationRepository.saveState(new LiveSimulationRepository.SimulationMetadata(
                SIMULATION_ID,
                progress.minusSeconds(ThroughputBucketService.BUCKET_SECONDS),
                com.flunav.backend.models.simulation.SimulationStatus.PLAYING,
                Instant.now(),
                progress,
                1.0,
                100.0));
    }

    private List<Message<?>> captureBrokerMessages(Runnable action) {
        List<Message<?>> messages = new ArrayList<>();
        ChannelInterceptor interceptor = new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                messages.add(message);
                return message;
            }
        };
        brokerChannel.addInterceptor(interceptor);
        try {
            action.run();
        } finally {
            brokerChannel.removeInterceptor(interceptor);
        }
        return messages;
    }

    private JsonNode throughputMetricFrom(List<Message<?>> messages, String simulationId) {
        String expectedDestination = "/topic/simulations/" + simulationId + "/analytics/throughput";
        return messages.stream()
                .filter(message -> expectedDestination.equals(
                        message.getHeaders().get("simpDestination", String.class)))
                .map(Message::getPayload)
                .map(this::payloadJson)
                .map(envelope -> envelope.path("payload"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing throughput message for " + expectedDestination));
    }

    private JsonNode payloadJson(Object payload) {
        try {
            if (payload instanceof byte[] bytes) {
                return objectMapper.readTree(bytes);
            }
            if (payload instanceof String text) {
                return objectMapper.readTree(text);
            }
            return objectMapper.valueToTree(payload);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to read broker payload", e);
        }
    }

    private Connection clickHouseConnection() throws Exception {
        return DriverManager.getConnection(
                CLICKHOUSE_CONTAINER.getJdbcUrl(),
                CLICKHOUSE_CONTAINER.getUsername(),
                CLICKHOUSE_CONTAINER.getPassword());
    }

    private int countLegacyAnalyticsTables(Statement statement) throws Exception {
        try (ResultSet tables = statement.executeQuery("""
                SELECT count()
                FROM system.tables
                WHERE database = 'default'
                  AND startsWith(name, 'analytics_time_series_legacy_')
                """)) {
            assertTrue(tables.next());
            return tables.getInt(1);
        }
    }

    private void resetState() {
        DatabaseContextHolder.clearSimulation();

        try {
            Objects.requireNonNull(redisTemplate.getConnectionFactory())
                    .getConnection()
                    .serverCommands()
                    .flushAll();
        } catch (Exception ignored) {
        }

        try {
            orientDBService.resetMainDatabaseForTests(SIMULATION_ID);
        } catch (Exception ignored) {
        }

        DatabaseContextHolder.clearSimulation();
    }

    private String loginToken() throws Exception {
        HttpResponse<String> response = httpClient.send(
                HttpRequest.newBuilder(URI.create(baseUrl() + "/api/auth/login"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"username\":\"admin\",\"password\":\"Flun4v!\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        return objectMapper.readTree(response.body()).get("token").asText();
    }

    private JsonNode findMetric(JsonNode metrics, String timestamp) {
        for (JsonNode metric : metrics) {
            if (timestamp.equals(metric.path("timestamp").asText())) {
                return metric;
            }
        }
        throw new IllegalStateException("Metric not found for " + timestamp);
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + serverPort;
    }
}
