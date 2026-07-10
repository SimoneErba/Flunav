package com.flunav.backend;

import com.flunav.backend.context.DatabaseContextHolder;
import com.flunav.backend.models.input.LocationInput;
import com.flunav.backend.models.response.BadActorMetric;
import com.flunav.backend.repositories.LiveItemRepository;
import com.flunav.backend.services.ClickHouseService;
import com.flunav.backend.services.ConveyorService;
import com.flunav.backend.services.EventProcessor;
import com.flunav.backend.services.LiveSystemScheduler;
import com.flunav.backend.services.LocationService;
import com.flunav.backend.services.OrientDBService;

import flunav.events.ChuteEmptyEvent;
import flunav.events.ItemCreatedEvent;
import flunav.events.ItemDeletedEvent;
import flunav.events.ItemDestinationEvent;
import flunav.events.ItemPathChangedEvent;
import flunav.events.ItemPositionChangedEvent;
import flunav.types.LocationType;
import flunav.types.PositionType;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestConstructor;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "springwolf.enabled=false",
        "app.demo-mode=false",
        "stale-item-cleanup.enabled=false",
        "state-recovery.enabled=false",
        "graph-snapshot.enabled=false"
})
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class AnalyticsMovementMetricsTests extends BaseIntegrationTest {

    private static final String SIMULATION_ID = "analytics-movement-sim";

    private final ClickHouseService clickHouseService;
    private final EventProcessor eventProcessor;
    private final LocationService locationService;
    private final ConveyorService conveyorService;
    private final OrientDBService orientDBService;
    private final LiveSystemScheduler liveSystemScheduler;
    private final LiveItemRepository liveItemRepository;
    private final StringRedisTemplate redisTemplate;

    AnalyticsMovementMetricsTests(
            ClickHouseService clickHouseService,
            EventProcessor eventProcessor,
            LocationService locationService,
            ConveyorService conveyorService,
            OrientDBService orientDBService,
            LiveSystemScheduler liveSystemScheduler,
            LiveItemRepository liveItemRepository,
            StringRedisTemplate redisTemplate) {
        this.clickHouseService = clickHouseService;
        this.eventProcessor = eventProcessor;
        this.locationService = locationService;
        this.conveyorService = conveyorService;
        this.orientDBService = orientDBService;
        this.liveSystemScheduler = liveSystemScheduler;
        this.liveItemRepository = liveItemRepository;
        this.redisTemplate = redisTemplate;
    }

    @BeforeEach
    void setup() throws Exception {
        resetState();
        truncateMovementAnalyticsTables();
    }

    @AfterEach
    void tearDown() throws Exception {
        liveSystemScheduler.cancelAll();
        truncateMovementAnalyticsTables();
        resetState();
    }

    @Test
    void completedConveyorTransitPersistsRawFactAndRollups() {
        Instant enteredAt = Instant.parse("2030-01-01T00:00:00Z");
        Instant arrivedAt = enteredAt.plusMillis(1_500);
        createLine("movement-live");

        eventProcessor.processEventWithoutBroadcast(new ItemCreatedEvent(
                "movement-item",
                "Movement Item",
                1.0,
                0.0,
                true,
                "movement-live-conveyor",
                PositionType.CONVEYOR,
                0.0,
                List.of("movement-live-end"),
                Map.of(),
                enteredAt));

        eventProcessor.processEvent(new ItemPositionChangedEvent(
                "movement-item",
                "movement-live-end",
                100.0,
                arrivedAt));
        clickHouseService.flushLocationTransitMetrics();

        List<Map<String, Object>> facts = clickHouseService.queryForList("""
                SELECT simulation_id, item_id, from_location_id, to_location_id, from_position_id, to_position_id, transit_time_ms, path
                FROM analytics_location_transit_events
                """);
        assertEquals(1, facts.size());
        Map<String, Object> fact = facts.get(0);
        assertEquals("live", fact.get("simulation_id"));
        assertEquals("movement-item", fact.get("item_id"));
        assertEquals("movement-live-start", fact.get("from_location_id"));
        assertEquals("movement-live-end", fact.get("to_location_id"));
        assertEquals("movement-live-conveyor", fact.get("from_position_id"));
        assertEquals("movement-live-end", fact.get("to_position_id"));
        assertEquals(1_500L, asLong(fact.get("transit_time_ms")));
        assertEquals(List.of("movement-live-start", "movement-live-end"), fact.get("path"));

        List<Map<String, Object>> locationCounts = clickHouseService.queryForList("""
                SELECT simulation_id, location_id, sum(items_transited) AS items_transited
                FROM analytics_location_transit_counts
                GROUP BY simulation_id, location_id
                """);
        assertEquals(1, locationCounts.size());
        assertEquals("live", locationCounts.get(0).get("simulation_id"));
        assertEquals("movement-live-end", locationCounts.get(0).get("location_id"));
        assertEquals(1L, asLong(locationCounts.get(0).get("items_transited")));

        List<Map<String, Object>> pathStats = clickHouseService.queryForList("""
                SELECT
                    simulation_id,
                    from_location_id,
                    to_location_id,
                    countMerge(sample_count_state) AS sample_count,
                    avgMerge(avg_transit_time_ms_state) AS avg_transit_time_ms,
                    stddevPopMerge(stddev_transit_time_ms_state) AS stddev_transit_time_ms,
                    quantileMerge(0.5)(median_transit_time_ms_state) AS median_transit_time_ms,
                    min(min_transit_time_ms) AS min_transit_time_ms,
                    max(max_transit_time_ms) AS max_transit_time_ms
                FROM analytics_path_transit_stats
                GROUP BY simulation_id, from_location_id, to_location_id
                """);
        assertEquals(1, pathStats.size());
        Map<String, Object> stats = pathStats.get(0);
        assertEquals("live", stats.get("simulation_id"));
        assertEquals("movement-live-start", stats.get("from_location_id"));
        assertEquals("movement-live-end", stats.get("to_location_id"));
        assertEquals(1L, asLong(stats.get("sample_count")));
        assertEquals(1_500.0, asDouble(stats.get("avg_transit_time_ms")));
        assertEquals(0.0, asDouble(stats.get("stddev_transit_time_ms")));
        assertEquals(1_500.0, asDouble(stats.get("median_transit_time_ms")));
        assertEquals(1_500L, asLong(stats.get("min_transit_time_ms")));
        assertEquals(1_500L, asLong(stats.get("max_transit_time_ms")));
    }

    @Test
    void movementAnalyticsAreIsolatedBySimulationId() {
        Instant enteredAt = Instant.parse("2030-01-01T01:00:00Z");
        Instant arrivedAt = enteredAt.plusMillis(2_000);
        createLine("movement-shared");

        eventProcessor.processEventWithoutBroadcast(new ItemCreatedEvent(
                "movement-shared-item",
                "Movement Shared Item",
                1.0,
                0.0,
                true,
                "movement-shared-conveyor",
                PositionType.CONVEYOR,
                0.0,
                List.of("movement-shared-end"),
                Map.of(),
                enteredAt));
        eventProcessor.processEvent(new ItemPositionChangedEvent(
                "movement-shared-item",
                "movement-shared-end",
                100.0,
                arrivedAt));

        orientDBService.createInMemoryDatabase(SIMULATION_ID);

        try (var ignored = DatabaseContextHolder.enterSimulationContext(SIMULATION_ID)) {
            createLine("movement-shared");
            eventProcessor.processEventWithoutBroadcast(new ItemCreatedEvent(
                    "movement-shared-item",
                    "Movement Sim Item",
                    1.0,
                    0.0,
                    true,
                    "movement-shared-conveyor",
                    PositionType.CONVEYOR,
                    0.0,
                    List.of("movement-shared-end"),
                    Map.of(),
                    enteredAt));
            eventProcessor.processEvent(new ItemPositionChangedEvent(
                    "movement-shared-item",
                    "movement-shared-end",
                    100.0,
                    arrivedAt));
        }
        clickHouseService.flushLocationTransitMetrics();

        List<Map<String, Object>> facts = clickHouseService.queryForList("""
                SELECT simulation_id, count() AS rows
                FROM analytics_location_transit_events
                WHERE item_id = 'movement-shared-item'
                GROUP BY simulation_id
                ORDER BY simulation_id
                """);
        assertEquals(2, facts.size());
        assertEquals("analytics-movement-sim", facts.get(0).get("simulation_id"));
        assertEquals(1L, asLong(facts.get(0).get("rows")));
        assertEquals("live", facts.get(1).get("simulation_id"));
        assertEquals(1L, asLong(facts.get(1).get("rows")));
    }

    @Test
    void itemPathChangedProducesLivePathTraversalMetric() {
        Instant now = Instant.parse("2030-01-01T02:00:00Z");
        createLine("path-change");

        eventProcessor.processEventWithoutBroadcast(new ItemCreatedEvent(
                "path-change-item",
                "Path Change Item",
                1.0,
                true,
                "path-change-start",
                PositionType.LOCATION,
                0.0,
                Map.of(),
                now));
        eventProcessor.processEventWithoutBroadcast(new ItemPathChangedEvent(
                "path-change-item",
                List.of("path-change-start", "path-change-end"),
                now.plusSeconds(1)));
        clickHouseService.flushPathTraversalMetrics();

        List<Map<String, Object>> journeys = journeyRows("path-change-item");
        assertEquals(1, journeys.size());
        Map<String, Object> journey = journeys.get(0);
        assertEquals("live", journey.get("simulation_id"));
        assertEquals(List.of("path-change-start", "path-change-end"), pathSegments(journey));
    }

    @Test
    void itemDestinationProducesLivePathTraversalMetric() {
        Instant now = Instant.parse("2030-01-01T03:00:00Z");
        createLine("destination-path");

        eventProcessor.processEventWithoutBroadcast(new ItemCreatedEvent(
                "destination-path-item",
                "Destination Path Item",
                1.0,
                true,
                "destination-path-start",
                PositionType.LOCATION,
                0.0,
                Map.of(),
                now));
        eventProcessor.processEventWithoutBroadcast(new ItemDestinationEvent(
                "destination-path-item",
                "destination-path-end",
                now.plusSeconds(1)));
        clickHouseService.flushPathTraversalMetrics();
        List<String> assignedPath = Objects.requireNonNull(
                liveItemRepository.getItemState("destination-path-item")).getPath();

        List<Map<String, Object>> journeys = journeyRows("destination-path-item");
        assertEquals(1, journeys.size());
        Map<String, Object> journey = journeys.get(0);
        assertEquals("live", journey.get("simulation_id"));
        assertEquals(assignedPath, pathSegments(journey));
    }

    @Test
    void chuteEmptyProducesPathTraversalMetricForRemovedItems() {
        Instant now = Instant.parse("2030-01-01T04:00:00Z");
        createLocation("path-chute", LocationType.CHUTE);

        eventProcessor.processEventWithoutBroadcast(new ItemCreatedEvent(
                "path-chute-item",
                "Path Chute Item",
                1.0,
                true,
                "path-chute",
                PositionType.LOCATION,
                0.0,
                Map.of(),
                now));
        eventProcessor.processEventWithoutBroadcast(new ChuteEmptyEvent("path-chute", now.plusSeconds(1)));
        clickHouseService.flushPathTraversalMetrics();

        List<Map<String, Object>> journeys = journeyRows("path-chute-item");
        assertEquals(1, journeys.size());
        assertEquals("live", journeys.get(0).get("simulation_id"));
        assertEquals(List.of("path-chute"), pathSegments(journeys.get(0)));
    }

    @Test
    void itemDeletedUpdatesPathMetricsWithoutPersistingPathTraversedToEvents() {
        Instant now = Instant.parse("2030-01-01T05:00:00Z");
        createLocation("path-delete-location");

        eventProcessor.processEventWithoutBroadcast(new ItemCreatedEvent(
                "path-delete-item",
                "Path Delete Item",
                1.0,
                true,
                "path-delete-location",
                PositionType.LOCATION,
                0.0,
                Map.of(),
                now));
        assertThrows(RuntimeException.class,
                () -> eventProcessor.processEventWithoutBroadcast(new ItemDeletedEvent("path-delete-item")));
        clickHouseService.flushEvents();
        clickHouseService.flushPathTraversalMetrics();

        assertTrue(clickHouseService.queryForList("""
                SELECT event_id
                FROM Events
                WHERE event_type = 'PATH_TRAVERSED'
                  AND entity_id = 'path-delete-item'
                """).isEmpty());

        List<Map<String, Object>> journeys = journeyRows("path-delete-item");
        assertEquals(1, journeys.size());
        assertEquals("live", journeys.get(0).get("simulation_id"));
        assertEquals(List.of("path-delete-location"), pathSegments(journeys.get(0)));
    }

    @Test
    void simulationPathTraversalMetricsUseSimulationScope() {
        Instant now = Instant.parse("2030-01-01T06:00:00Z");
        orientDBService.createInMemoryDatabase(SIMULATION_ID);

        try (var ignored = DatabaseContextHolder.enterSimulationContext(SIMULATION_ID)) {
            createLine("path-sim");
            eventProcessor.processEventWithoutBroadcast(new ItemCreatedEvent(
                    "path-sim-item",
                    "Path Sim Item",
                    1.0,
                    true,
                    "path-sim-start",
                    PositionType.LOCATION,
                    0.0,
                    Map.of(),
                    now));
            eventProcessor.processEventWithoutBroadcast(new ItemPathChangedEvent(
                    "path-sim-item",
                    List.of("path-sim-start", "path-sim-end"),
                    now.plusSeconds(1)));
        }
        clickHouseService.flushPathTraversalMetrics();

        List<Map<String, Object>> journeys = journeyRows("path-sim-item");
        assertEquals(1, journeys.size());
        assertEquals(SIMULATION_ID, journeys.get(0).get("simulation_id"));
        assertEquals(List.of("path-sim-start", "path-sim-end"), pathSegments(journeys.get(0)));
    }

    @Test
    void topActiveComponentsAreScopedBySimulationId() {
        Instant now = Instant.parse("2030-01-01T07:00:00Z");
        createLine("component-live");

        eventProcessor.processEventWithoutBroadcast(new ItemCreatedEvent(
                "component-live-item",
                "Component Live Item",
                1.0,
                true,
                "component-live-start",
                PositionType.LOCATION,
                0.0,
                Map.of(),
                now));
        eventProcessor.processEventWithoutBroadcast(new ItemPathChangedEvent(
                "component-live-item",
                List.of("component-live-start", "component-live-end"),
                now.plusSeconds(1)));

        orientDBService.createInMemoryDatabase(SIMULATION_ID);
        try (var ignored = DatabaseContextHolder.enterSimulationContext(SIMULATION_ID)) {
            createLine("component-sim");
            eventProcessor.processEventWithoutBroadcast(new ItemCreatedEvent(
                    "component-sim-item",
                    "Component Sim Item",
                    1.0,
                    true,
                    "component-sim-start",
                    PositionType.LOCATION,
                    0.0,
                    Map.of(),
                    now));
            eventProcessor.processEventWithoutBroadcast(new ItemPathChangedEvent(
                    "component-sim-item",
                    List.of("component-sim-start", "component-sim-end"),
                    now.plusSeconds(1)));
        }
        clickHouseService.flushPathTraversalMetrics();

        List<BadActorMetric> liveComponents = clickHouseService.getTopActiveComponents(10).join();
        assertTrue(liveComponents.stream().anyMatch(metric -> "component-live-start".equals(metric.getLocationId())));
        assertTrue(liveComponents.stream().noneMatch(metric -> "component-sim-start".equals(metric.getLocationId())));

        try (var ignored = DatabaseContextHolder.enterSimulationContext(SIMULATION_ID)) {
            List<BadActorMetric> simulationComponents = clickHouseService.getTopActiveComponents(10).join();
            assertTrue(simulationComponents.stream()
                    .anyMatch(metric -> "component-sim-start".equals(metric.getLocationId())));
            assertTrue(simulationComponents.stream()
                    .noneMatch(metric -> "component-live-start".equals(metric.getLocationId())));
        }
    }

    private void createLine(String prefix) {
        createLocation(prefix + "-start");
        createLocation(prefix + "-end");
        conveyorService.createConveyor(
                prefix + "-conveyor",
                prefix + "-start",
                prefix + "-end",
                prefix + "-conveyor",
                10.0,
                1.0,
                0.0,
                true,
                true);
    }

    private void createLocation(String id) {
        createLocation(id, LocationType.GENERIC);
    }

    private void createLocation(String id, LocationType type) {
        locationService.createLocation(new LocationInput(id, id, 0.0, 0.0, null, null, type, 100,
                true, false, Map.of()));
    }

    private void truncateMovementAnalyticsTables() throws Exception {
        clickHouseService.flushLocationTransitMetrics();
        clickHouseService.flushPathTraversalMetrics();
        try (Connection connection = clickHouseConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("TRUNCATE TABLE item_journeys");
            statement.execute("TRUNCATE TABLE analytics_components");
            statement.execute("TRUNCATE TABLE analytics_location_transit_events");
            statement.execute("TRUNCATE TABLE analytics_location_transit_counts");
            statement.execute("TRUNCATE TABLE analytics_path_transit_stats");
        }
    }

    private List<Map<String, Object>> journeyRows(String itemId) {
        return clickHouseService.queryForList("""
                SELECT
                    simulation_id,
                    item_id,
                    groupArrayArrayMerge(path_segments) AS path_segments
                FROM item_journeys
                WHERE item_id = '%s'
                GROUP BY simulation_id, item_id
                ORDER BY simulation_id
                """.formatted(itemId));
    }

    @SuppressWarnings("unchecked")
    private List<String> pathSegments(Map<String, Object> journey) {
        return (List<String>) journey.get("path_segments");
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

    private Connection clickHouseConnection() throws Exception {
        return DriverManager.getConnection(
                CLICKHOUSE_CONTAINER.getJdbcUrl(),
                CLICKHOUSE_CONTAINER.getUsername(),
                CLICKHOUSE_CONTAINER.getPassword());
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
}
