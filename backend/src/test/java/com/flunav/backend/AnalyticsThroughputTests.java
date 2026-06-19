package com.flunav.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flunav.backend.context.DatabaseContextHolder;
import com.flunav.backend.models.input.LocationInput;
import com.flunav.backend.models.response.ThroughputMetric;
import com.flunav.backend.repositories.LiveItemRepository;
import com.flunav.backend.repositories.LiveLocationRepository;
import com.flunav.backend.services.ClickHouseService;
import com.flunav.backend.services.EventProcessor;
import com.flunav.backend.services.LocationService;
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
import org.springframework.test.context.TestConstructor;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
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
    private final StringRedisTemplate redisTemplate;
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
            StringRedisTemplate redisTemplate,
            @LocalServerPort int serverPort) {
        this.clickHouseService = clickHouseService;
        this.eventProcessor = eventProcessor;
        this.throughputBucketService = throughputBucketService;
        this.locationService = locationService;
        this.orientDBService = orientDBService;
        this.liveItemRepository = liveItemRepository;
        this.liveLocationRepository = liveLocationRepository;
        this.redisTemplate = redisTemplate;
        this.serverPort = serverPort;
    }

    @BeforeEach
    void setup() throws Exception {
        resetState();
        truncateAnalyticsTables();
    }

    @AfterEach
    void tearDown() throws Exception {
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
        try (Connection connection = DriverManager.getConnection(
                CLICKHOUSE_CONTAINER.getJdbcUrl(),
                CLICKHOUSE_CONTAINER.getUsername(),
                CLICKHOUSE_CONTAINER.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("TRUNCATE TABLE Events");
            statement.execute("TRUNCATE TABLE analytics_time_series");
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
