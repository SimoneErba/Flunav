package com.flunav.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flunav.backend.context.DatabaseContextHolder;
import com.flunav.backend.domain.Role;
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
import com.flunav.backend.services.StaleItemCleanupService;
import com.flunav.backend.services.ThroughputBucketService;
import com.flunav.backend.services.TimeService;
import com.flunav.backend.utils.JwtUtils;

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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "springwolf.enabled=false",
        "app.demo-mode=false",
        "state-recovery.enabled=false",
        "graph-snapshot.enabled=false"
})
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class AnalyticsThroughputTests extends BaseIntegrationTest {

    private static final String SIMULATION_ID = "analytics-throughput-sim";

    private final ClickHouseService clickHouseService;
    private final EventProcessor eventProcessor;
    private final ThroughputBucketService throughputBucketService;
    private final TimeService timeService;
    private final LocationService locationService;
    private final OrientDBService orientDBService;
    private final LiveItemRepository liveItemRepository;
    private final LiveLocationRepository liveLocationRepository;
    private final LiveSimulationRepository liveSimulationRepository;
    private final LiveSystemScheduler liveSystemScheduler;
    private final StaleItemCleanupService staleItemCleanupService;
    private final JwtUtils jwtUtils;
    private final StringRedisTemplate redisTemplate;
    private final AbstractMessageChannel brokerChannel;
    private final int serverPort;
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    AnalyticsThroughputTests(
            ClickHouseService clickHouseService,
            EventProcessor eventProcessor,
            ThroughputBucketService throughputBucketService,
            TimeService timeService,
            LocationService locationService,
            OrientDBService orientDBService,
            LiveItemRepository liveItemRepository,
            LiveLocationRepository liveLocationRepository,
            LiveSimulationRepository liveSimulationRepository,
            LiveSystemScheduler liveSystemScheduler,
            StaleItemCleanupService staleItemCleanupService,
            JwtUtils jwtUtils,
            StringRedisTemplate redisTemplate,
            @org.springframework.beans.factory.annotation.Qualifier("brokerChannel")
            AbstractMessageChannel brokerChannel,
            @LocalServerPort int serverPort) {
        this.clickHouseService = clickHouseService;
        this.eventProcessor = eventProcessor;
        this.throughputBucketService = throughputBucketService;
        this.timeService = timeService;
        this.locationService = locationService;
        this.orientDBService = orientDBService;
        this.liveItemRepository = liveItemRepository;
        this.liveLocationRepository = liveLocationRepository;
        this.liveSimulationRepository = liveSimulationRepository;
        this.liveSystemScheduler = liveSystemScheduler;
        this.staleItemCleanupService = staleItemCleanupService;
        this.jwtUtils = jwtUtils;
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
        timeService.reset();
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
    void administrativeItemDeletionDoesNotIncrementExited() {
        createLocation("analytics-delete-start", LocationType.GENERIC, 100);
        liveItemRepository.saveItemState("analytics-deleted-item", "analytics-delete-start", PositionType.LOCATION,
                Instant.now(), 0.0, "Deleted", List.of(), null, null);
        assertEquals(1, liveItemRepository.countActiveItems());

        ItemDeletedEvent deleteEvent = new ItemDeletedEvent("analytics-deleted-item");
        liveItemRepository.deleteItem("analytics-deleted-item");
        throughputBucketService.recordSuccessfulReduction(deleteEvent, Map.of("status", "PROCESSED_SUCCESSFULLY"));
        throughputBucketService.flushBuckets();

        assertTrue(clickHouseService.getThroughputHistory(1).join().isEmpty());
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
    void staleChuteCleanupRemovesAllHotStateAndIncrementsExitedMetric() {
        Instant cleanupReference = timeService.physicalNow();
        Instant staleEntry = cleanupReference.minusSeconds(11 * 60L);
        createLocation("analytics-stale-chute", LocationType.CHUTE, 10);
        eventProcessor.processEventWithoutBroadcast(new ItemCreatedEvent(
                "analytics-stale-item", "Stale Item", 1.0, true,
                "analytics-stale-chute", PositionType.LOCATION, 0.0, Map.of(), staleEntry));
        assertEquals(1, liveLocationRepository.getItemCount("analytics-stale-chute"));

        staleItemCleanupService.cleanupStaleItemsAtExits();
        throughputBucketService.flushBuckets();

        assertNull(liveItemRepository.getItemState("analytics-stale-item"));
        assertEquals(0, liveLocationRepository.getItemCount("analytics-stale-chute"));
        ThroughputMetric exitMetric = clickHouseService.getThroughputHistory(1).join().stream()
                .filter(metric -> metric.getItemsExited() == 1)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing stale-cleanup exit metric"));
        assertEquals(0, exitMetric.getItemsEntered());
        assertEquals(0, exitMetric.getItemsCurrent());
    }

    @Test
    void completedIdleBucketResetsRatesAndRefreshesCurrentCount() {
        Instant firstProgress = Instant.parse("2030-01-01T00:00:10Z");
        Instant eventTimestamp = firstProgress.minusSeconds(ThroughputBucketService.BUCKET_SECONDS);
        timeService.useFixedClock(firstProgress);
        createLocation("analytics-idle-start", LocationType.GENERIC, 100);

        List<Message<?>> firstMessages = captureBrokerMessages(() -> {
            eventProcessor.processEvent(new ItemCreatedEvent("analytics-idle-item", "Idle", 1.0, true,
                    "analytics-idle-start", PositionType.LOCATION, 0.0, Map.of(), eventTimestamp));
            throughputBucketService.flushCompletedBuckets();
        });

        JsonNode activeMetric = throughputMetricFrom(firstMessages, null);
        assertEquals(1, activeMetric.path("itemsEntered").asLong());
        assertEquals(1, activeMetric.path("itemsCurrent").asLong());

        liveItemRepository.deleteItem("analytics-idle-item");
        timeService.useFixedClock(firstProgress.plusSeconds(ThroughputBucketService.BUCKET_SECONDS));

        List<Message<?>> idleMessages = captureBrokerMessages(throughputBucketService::flushCompletedBuckets);
        JsonNode idleMetric = throughputMetricFrom(idleMessages, null);
        assertEquals(0, idleMetric.path("itemsEntered").asLong());
        assertEquals(0, idleMetric.path("itemsExited").asLong());
        assertEquals(0, idleMetric.path("itemsCurrent").asLong());
    }

    @Test
    void simulationBucketsAreNotPersistedToClickHouse() {
        Instant timestamp = Instant.now().plus(Duration.ofHours(1));
        Instant simulatedBucketStart = bucketStart(timestamp);
        orientDBService.createInMemoryDatabase(SIMULATION_ID);

        try (var ignored = DatabaseContextHolder.enterSimulationContext(SIMULATION_ID)) {
            createLocation("analytics-sim-start", LocationType.GENERIC, 100);
            eventProcessor.processEvent(new ItemCreatedEvent("analytics-sim-item", "Sim Item", 1.0, true,
                    "analytics-sim-start", PositionType.LOCATION, 0.0, Map.of(), timestamp));
        }
        throughputBucketService.flushBuckets();

        List<ThroughputMetric> metrics = clickHouseService.getThroughputHistory(
                simulatedBucketStart,
                simulatedBucketStart.plusSeconds(ThroughputBucketService.BUCKET_SECONDS),
                ThroughputBucketService.BUCKET_SECONDS).join();
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

        JsonNode metric = throughputMetricFrom(messages, SIMULATION_ID, bucketStart(eventTimestamp));
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
        assertFalse(hasThroughputMessage(messages, null, bucketStart(eventTimestamp)),
                "Simulation throughput must not be broadcast on the live throughput topic");
    }

    @Test
    void liveBucketFlushesOnlyToLiveWebSocketTopic() {
        Instant eventTimestamp = Instant.now().minus(Duration.ofSeconds(10));
        createLocation("analytics-live-topic-start", LocationType.GENERIC, 100);

        List<Message<?>> messages = captureBrokerMessages(() -> {
            eventProcessor.processEvent(new ItemCreatedEvent("analytics-live-topic-item", "Live Topic", 1.0, true,
                    "analytics-live-topic-start", PositionType.LOCATION, 0.0, Map.of(), eventTimestamp));
            throughputBucketService.flushCompletedBuckets();
        });

        JsonNode metric = throughputMetricFrom(messages, null, bucketStart(eventTimestamp));
        assertEquals(1, metric.path("itemsEntered").asLong());
        assertFalse(hasThroughputMessage(messages, SIMULATION_ID, bucketStart(eventTimestamp)),
                "Live throughput must not be broadcast on simulation throughput topics");
    }

    @Test
    void simulationHttpItemCreationUsesSimulationTimeAndPublishesOnlyToSimulationTopic() throws Exception {
        Instant restoreTimestamp = Instant.parse("2030-04-01T00:00:00Z");
        saveSimulationState(restoreTimestamp, restoreTimestamp);
        orientDBService.createInMemoryDatabase(SIMULATION_ID);

        try (var ignored = DatabaseContextHolder.enterSimulationContext(SIMULATION_ID)) {
            createLocation("analytics-sim-http-start", LocationType.GENERIC, 100);
        }

        List<Message<?>> messages = captureBrokerMessages(() -> {
            try {
                HttpResponse<String> response = httpClient.send(
                        HttpRequest.newBuilder(URI.create(baseUrl() + "/api/items"))
                                .header("Authorization", authorizationHeader())
                                .header("Content-Type", "application/json")
                                .header("X-Simulation-ID", SIMULATION_ID)
                                .POST(HttpRequest.BodyPublishers.ofString("""
                                        {
                                          "id": "analytics-sim-http-item",
                                          "name": "Simulation HTTP Item",
                                          "speed": 1.0,
                                          "priority": 0.0,
                                          "active": true,
                                          "locationId": "analytics-sim-http-start",
                                          "positionType": "LOCATION",
                                          "progress": 0.0,
                                          "destinations": [],
                                          "properties": {}
                                        }
                                        """))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                assertTrue(response.statusCode() >= 200 && response.statusCode() < 300);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        JsonNode metric = throughputMetricFrom(messages, SIMULATION_ID, bucketStart(restoreTimestamp));
        assertEquals(1, metric.path("itemsEntered").asLong());
        assertEquals(1, metric.path("itemsCurrent").asLong());
        assertFalse(hasThroughputMessage(messages, null, bucketStart(restoreTimestamp)),
                "Simulation HTTP item creation must not publish throughput on the live topic");
    }

    @Test
    void simulationIdleBucketResetsRatesAtVirtualProgress() {
        Instant eventTimestamp = Instant.now().plus(Duration.ofHours(2));
        saveSimulationState(eventTimestamp.plusSeconds(ThroughputBucketService.BUCKET_SECONDS));
        orientDBService.createInMemoryDatabase(SIMULATION_ID);

        captureBrokerMessages(() -> {
            try (var ignored = DatabaseContextHolder.enterSimulationContext(SIMULATION_ID)) {
                createLocation("analytics-sim-idle-start", LocationType.GENERIC, 100);
                eventProcessor.processEvent(new ItemCreatedEvent("analytics-sim-idle-item", "Sim Idle", 1.0, true,
                        "analytics-sim-idle-start", PositionType.LOCATION, 0.0, Map.of(), eventTimestamp));
            }
            throughputBucketService.flushCompletedBuckets();
        });

        try (var ignored = DatabaseContextHolder.enterSimulationContext(SIMULATION_ID)) {
            liveItemRepository.deleteItem("analytics-sim-idle-item");
        }
        saveSimulationState(eventTimestamp.plusSeconds(ThroughputBucketService.BUCKET_SECONDS * 2L));

        List<Message<?>> idleMessages = captureBrokerMessages(throughputBucketService::flushCompletedBuckets);
        JsonNode idleMetric = throughputMetricFrom(idleMessages, SIMULATION_ID);
        assertEquals(0, idleMetric.path("itemsEntered").asLong());
        assertEquals(0, idleMetric.path("itemsExited").asLong());
        assertEquals(0, idleMetric.path("itemsCurrent").asLong());
    }

    @Test
    void simulationHistoryRecoversEmittedBuckets() {
        Instant eventTimestamp = Instant.now().minusSeconds(10);
        saveSimulationState(eventTimestamp.plusSeconds(ThroughputBucketService.BUCKET_SECONDS));
        orientDBService.createInMemoryDatabase(SIMULATION_ID);

        try (var ignored = DatabaseContextHolder.enterSimulationContext(SIMULATION_ID)) {
            createLocation("analytics-sim-history-start", LocationType.GENERIC, 100);
            eventProcessor.processEvent(new ItemCreatedEvent("analytics-sim-history-item", "Sim History", 1.0, true,
                    "analytics-sim-history-start", PositionType.LOCATION, 0.0, Map.of(), eventTimestamp));
        }
        throughputBucketService.flushCompletedBuckets();

        ThroughputMetric metric = throughputBucketService.getSimulationHistory(
                SIMULATION_ID,
                eventTimestamp.minusSeconds(ThroughputBucketService.BUCKET_SECONDS),
                eventTimestamp.plusSeconds(ThroughputBucketService.BUCKET_SECONDS),
                ThroughputBucketService.BUCKET_SECONDS)
                .join()
                .stream()
                .filter(candidate -> bucketStart(eventTimestamp).equals(candidate.getTimestamp()))
                .findFirst()
                .orElseThrow();

        assertEquals(1, metric.getItemsEntered());
        assertEquals(0, metric.getItemsExited());
        assertEquals(1, metric.getItemsCurrent());
    }

    @Test
    void liveHistoryEndpointUsesClickHouseWhenNoSimulationContextIsPresent() throws Exception {
        Instant timestamp = Instant.parse("2030-02-01T00:00:05Z");
        clickHouseService.saveThroughputMetric(new ThroughputMetric(
                bucketStart(timestamp),
                7,
                2,
                5,
                ThroughputBucketService.BUCKET_SECONDS));

        HttpResponse<String> response = sendThroughputHistoryRequest(
                timestamp.minusSeconds(5),
                timestamp.plusSeconds(5),
                ThroughputBucketService.BUCKET_SECONDS,
                null);

        assertEquals(200, response.statusCode());
        JsonNode metric = findMetric(objectMapper.readTree(response.body()), bucketStart(timestamp).toString());
        assertEquals(7, metric.path("itemsEntered").asLong());
        assertEquals(2, metric.path("itemsExited").asLong());
        assertEquals(5, metric.path("itemsCurrent").asLong());
    }

    @Test
    void simulationHistoryEndpointMergesLiveBeforeRestoreAndSimulationBucketsOnlyAfterRestore() throws Exception {
        Instant restoreTimestamp = Instant.parse("2030-02-01T00:00:10Z");
        Instant liveBeforeRestore = Instant.parse("2030-02-01T00:00:05Z");
        Instant liveAfterRestore = Instant.parse("2030-02-01T00:00:15Z");
        Instant simulationTimestamp = Instant.parse("2030-02-01T00:00:20Z");

        clickHouseService.saveThroughputMetric(new ThroughputMetric(
                bucketStart(liveBeforeRestore),
                3,
                0,
                3,
                ThroughputBucketService.BUCKET_SECONDS));
        clickHouseService.saveThroughputMetric(new ThroughputMetric(
                bucketStart(liveAfterRestore),
                9,
                0,
                9,
                ThroughputBucketService.BUCKET_SECONDS));

        saveSimulationState(restoreTimestamp, simulationTimestamp.plusSeconds(ThroughputBucketService.BUCKET_SECONDS));
        orientDBService.createInMemoryDatabase(SIMULATION_ID);
        try (var ignored = DatabaseContextHolder.enterSimulationContext(SIMULATION_ID)) {
            createLocation("analytics-sim-merge-start", LocationType.GENERIC, 100);
            eventProcessor.processEvent(new ItemCreatedEvent("analytics-sim-merge-item", "Sim Merge", 1.0, true,
                    "analytics-sim-merge-start", PositionType.LOCATION, 0.0, Map.of(), simulationTimestamp));
        }
        throughputBucketService.flushBuckets();

        HttpResponse<String> response = sendThroughputHistoryRequest(
                restoreTimestamp.minusSeconds(10),
                simulationTimestamp.plusSeconds(5),
                ThroughputBucketService.BUCKET_SECONDS,
                SIMULATION_ID);

        assertEquals(200, response.statusCode());
        JsonNode metrics = objectMapper.readTree(response.body());

        JsonNode liveMetric = findMetric(metrics, bucketStart(liveBeforeRestore).toString());
        assertEquals(3, liveMetric.path("itemsEntered").asLong());

        JsonNode simulationMetric = findMetric(metrics, bucketStart(simulationTimestamp).toString());
        assertEquals(1, simulationMetric.path("itemsEntered").asLong());
        assertEquals(1, simulationMetric.path("itemsCurrent").asLong());

        assertFalse(hasMetric(metrics, bucketStart(liveAfterRestore).toString()),
                "Simulation history must exclude live ClickHouse buckets after the restore timestamp");
    }

    @Test
    void cleanupSimulationHistoryRemovesQueuedAndEmittedSimulationBucketsWithoutTouchingLiveHistory() throws Exception {
        Instant restoreTimestamp = Instant.parse("2030-03-01T00:00:00Z");
        Instant queuedTimestamp = Instant.parse("2030-03-01T00:00:05Z");
        Instant emittedTimestamp = Instant.parse("2030-03-01T00:00:10Z");
        Instant liveTimestamp = Instant.parse("2030-03-01T00:00:15Z");

        clickHouseService.saveThroughputMetric(new ThroughputMetric(
                bucketStart(liveTimestamp),
                4,
                0,
                4,
                ThroughputBucketService.BUCKET_SECONDS));

        saveSimulationState(restoreTimestamp, emittedTimestamp.plusSeconds(ThroughputBucketService.BUCKET_SECONDS));
        orientDBService.createInMemoryDatabase(SIMULATION_ID);
        try (var ignored = DatabaseContextHolder.enterSimulationContext(SIMULATION_ID)) {
            createLocation("analytics-sim-cleanup-start", LocationType.GENERIC, 100);
            eventProcessor.processEvent(new ItemCreatedEvent("analytics-sim-cleanup-queued", "Queued", 1.0, true,
                    "analytics-sim-cleanup-start", PositionType.LOCATION, 0.0, Map.of(), queuedTimestamp));
        }
        throughputBucketService.cleanupSimulationHistory(SIMULATION_ID);
        throughputBucketService.flushBuckets();

        assertTrue(throughputBucketService.getSimulationHistory(
                SIMULATION_ID,
                queuedTimestamp.minusSeconds(5),
                queuedTimestamp.plusSeconds(5),
                ThroughputBucketService.BUCKET_SECONDS)
                .join()
                .isEmpty());

        try (var ignored = DatabaseContextHolder.enterSimulationContext(SIMULATION_ID)) {
            eventProcessor.processEvent(new ItemCreatedEvent("analytics-sim-cleanup-emitted", "Emitted", 1.0, true,
                    "analytics-sim-cleanup-start", PositionType.LOCATION, 0.0, Map.of(), emittedTimestamp));
        }
        throughputBucketService.flushBuckets();
        assertFalse(throughputBucketService.getSimulationHistory(
                SIMULATION_ID,
                emittedTimestamp.minusSeconds(5),
                emittedTimestamp.plusSeconds(5),
                ThroughputBucketService.BUCKET_SECONDS)
                .join()
                .isEmpty());

        throughputBucketService.cleanupSimulationHistory(SIMULATION_ID);
        assertTrue(throughputBucketService.getSimulationHistory(
                SIMULATION_ID,
                emittedTimestamp.minusSeconds(5),
                emittedTimestamp.plusSeconds(5),
                ThroughputBucketService.BUCKET_SECONDS)
                .join()
                .isEmpty());

        HttpResponse<String> liveResponse = sendThroughputHistoryRequest(
                liveTimestamp.minusSeconds(5),
                liveTimestamp.plusSeconds(5),
                ThroughputBucketService.BUCKET_SECONDS,
                null);
        assertEquals(200, liveResponse.statusCode());
        JsonNode liveMetric = findMetric(objectMapper.readTree(liveResponse.body()), bucketStart(liveTimestamp).toString());
        assertEquals(4, liveMetric.path("itemsEntered").asLong());
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
                        .header("Authorization", authorizationHeader())
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

    @Test
    void throughputHistoryEndpointRejectsInvalidBucketSeconds() throws Exception {
        assertEquals(400, sendThroughputHistoryRequest(
                "/api/analytics/throughput/history?hours=1&bucketSeconds=0",
                null).statusCode());
        assertEquals(400, sendThroughputHistoryRequest(
                "/api/analytics/throughput/history?hours=1&bucketSeconds=3601",
                null).statusCode());
    }

    @Test
    void throughputHistoryEndpointRejectsFromAfterTo() throws Exception {
        assertEquals(400, sendThroughputHistoryRequest(
                "/api/analytics/throughput/history?from=2030-01-01T00:00:10Z&to=2030-01-01T00:00:00Z&bucketSeconds=5",
                null).statusCode());
    }

    @Test
    void throughputHistoryEndpointClampsNonPositiveHoursToOneHour() throws Exception {
        Instant timestamp = Instant.now().minus(Duration.ofMinutes(30));
        clickHouseService.saveThroughputMetric(new ThroughputMetric(
                bucketStart(timestamp),
                2,
                0,
                2,
                ThroughputBucketService.BUCKET_SECONDS));

        HttpResponse<String> response = sendThroughputHistoryRequest(
                "/api/analytics/throughput/history?hours=0&bucketSeconds=5",
                null);

        assertEquals(200, response.statusCode());
        JsonNode metric = findMetric(objectMapper.readTree(response.body()), bucketStart(timestamp).toString());
        assertEquals(2, metric.path("itemsEntered").asLong());
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
        throughputBucketService.resetInMemoryState();
        throughputBucketService.flushBuckets();
        clickHouseService.flushEvents();
        try (Connection connection = clickHouseConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("TRUNCATE TABLE Events");
            statement.execute("TRUNCATE TABLE analytics_time_series");
        }
    }

    private void saveSimulationState(Instant progress) {
        saveSimulationState(progress.minusSeconds(ThroughputBucketService.BUCKET_SECONDS), progress);
    }

    private void saveSimulationState(Instant restoreTimestamp, Instant progress) {
        liveSimulationRepository.saveState(new LiveSimulationRepository.SimulationMetadata(
                SIMULATION_ID,
                restoreTimestamp,
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
        return throughputMetricFrom(messages, simulationId, null);
    }

    private JsonNode throughputMetricFrom(
            List<Message<?>> messages,
            String simulationId,
            Instant expectedTimestamp) {
        String expectedDestination = simulationId == null
                ? "/topic/analytics/throughput"
                : "/topic/simulations/" + simulationId + "/analytics/throughput";
        return messages.stream()
                .filter(message -> expectedDestination.equals(
                        message.getHeaders().get("simpDestination", String.class)))
                .map(Message::getPayload)
                .map(this::payloadJson)
                .map(envelope -> envelope.path("payload"))
                .filter(payload -> expectedTimestamp == null
                        || expectedTimestamp.toString().equals(payload.path("timestamp").asText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing throughput message for " + expectedDestination));
    }

    private boolean hasThroughputMessage(List<Message<?>> messages, String simulationId, Instant expectedTimestamp) {
        String expectedDestination = simulationId == null
                ? "/topic/analytics/throughput"
                : "/topic/simulations/" + simulationId + "/analytics/throughput";
        return messages.stream()
                .anyMatch(message -> expectedDestination.equals(
                        message.getHeaders().get("simpDestination", String.class))
                        && expectedTimestamp.toString().equals(
                                payloadJson(message.getPayload()).path("payload").path("timestamp").asText()));
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
        throughputBucketService.resetInMemoryState();
        throughputBucketService.cleanupSimulationHistory(SIMULATION_ID);

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

    private String authorizationHeader() {
        return "Bearer " + jwtUtils.generateToken("analytics-throughput-test", Role.ADMIN);
    }

    private HttpResponse<String> sendThroughputHistoryRequest(
            Instant from,
            Instant to,
            int bucketSeconds,
            String simulationId) throws Exception {
        String path = "/api/analytics/throughput/history?from=%s&to=%s&bucketSeconds=%d"
                .formatted(from, to, bucketSeconds);
        return sendThroughputHistoryRequest(path, simulationId);
    }

    private HttpResponse<String> sendThroughputHistoryRequest(String path, String simulationId) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .header("Authorization", authorizationHeader())
                .GET();
        if (simulationId != null) {
            builder.header("X-Simulation-ID", simulationId);
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode findMetric(JsonNode metrics, String timestamp) {
        for (JsonNode metric : metrics) {
            if (timestamp.equals(metric.path("timestamp").asText())) {
                return metric;
            }
        }
        throw new IllegalStateException("Metric not found for " + timestamp);
    }

    private boolean hasMetric(JsonNode metrics, String timestamp) {
        for (JsonNode metric : metrics) {
            if (timestamp.equals(metric.path("timestamp").asText())) {
                return true;
            }
        }
        return false;
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + serverPort;
    }
}
