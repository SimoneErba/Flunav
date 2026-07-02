package com.flunav.backend;

import com.flunav.backend.context.DatabaseContextHolder;
import com.flunav.backend.models.input.LocationInput;
import com.flunav.backend.services.ClickHouseService;
import com.flunav.backend.services.ConveyorService;
import com.flunav.backend.services.EventProcessor;
import com.flunav.backend.services.LiveSystemScheduler;
import com.flunav.backend.services.LocationService;
import com.flunav.backend.services.OrientDBService;

import flunav.events.ItemCreatedEvent;
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
    private final StringRedisTemplate redisTemplate;

    AnalyticsMovementMetricsTests(
            ClickHouseService clickHouseService,
            EventProcessor eventProcessor,
            LocationService locationService,
            ConveyorService conveyorService,
            OrientDBService orientDBService,
            LiveSystemScheduler liveSystemScheduler,
            StringRedisTemplate redisTemplate) {
        this.clickHouseService = clickHouseService;
        this.eventProcessor = eventProcessor;
        this.locationService = locationService;
        this.conveyorService = conveyorService;
        this.orientDBService = orientDBService;
        this.liveSystemScheduler = liveSystemScheduler;
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
                SELECT item_id, from_location_id, to_location_id, from_position_id, to_position_id, transit_time_ms, path
                FROM analytics_location_transit_events
                """);
        assertEquals(1, facts.size());
        Map<String, Object> fact = facts.get(0);
        assertEquals("movement-item", fact.get("item_id"));
        assertEquals("movement-live-start", fact.get("from_location_id"));
        assertEquals("movement-live-end", fact.get("to_location_id"));
        assertEquals("movement-live-conveyor", fact.get("from_position_id"));
        assertEquals("movement-live-end", fact.get("to_position_id"));
        assertEquals(1_500L, asLong(fact.get("transit_time_ms")));
        assertEquals(List.of("movement-live-start", "movement-live-end"), fact.get("path"));

        List<Map<String, Object>> locationCounts = clickHouseService.queryForList("""
                SELECT location_id, sum(items_transited) AS items_transited
                FROM analytics_location_transit_counts
                GROUP BY location_id
                """);
        assertEquals(1, locationCounts.size());
        assertEquals("movement-live-end", locationCounts.get(0).get("location_id"));
        assertEquals(1L, asLong(locationCounts.get(0).get("items_transited")));

        List<Map<String, Object>> pathStats = clickHouseService.queryForList("""
                SELECT
                    from_location_id,
                    to_location_id,
                    countMerge(sample_count_state) AS sample_count,
                    avgMerge(avg_transit_time_ms_state) AS avg_transit_time_ms,
                    stddevPopMerge(stddev_transit_time_ms_state) AS stddev_transit_time_ms,
                    quantileMerge(0.5)(median_transit_time_ms_state) AS median_transit_time_ms,
                    min(min_transit_time_ms) AS min_transit_time_ms,
                    max(max_transit_time_ms) AS max_transit_time_ms
                FROM analytics_path_transit_stats
                GROUP BY from_location_id, to_location_id
                """);
        assertEquals(1, pathStats.size());
        Map<String, Object> stats = pathStats.get(0);
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
    void simulationTransitIsNotPersistedToLiveAnalytics() {
        Instant enteredAt = Instant.parse("2030-01-01T01:00:00Z");
        Instant arrivedAt = enteredAt.plusMillis(2_000);
        orientDBService.createInMemoryDatabase(SIMULATION_ID);

        try (var ignored = DatabaseContextHolder.enterSimulationContext(SIMULATION_ID)) {
            createLine("movement-sim");
            eventProcessor.processEventWithoutBroadcast(new ItemCreatedEvent(
                    "movement-sim-item",
                    "Movement Sim Item",
                    1.0,
                    0.0,
                    true,
                    "movement-sim-conveyor",
                    PositionType.CONVEYOR,
                    0.0,
                    List.of("movement-sim-end"),
                    Map.of(),
                    enteredAt));
            eventProcessor.processEvent(new ItemPositionChangedEvent(
                    "movement-sim-item",
                    "movement-sim-end",
                    100.0,
                    arrivedAt));
        }
        clickHouseService.flushLocationTransitMetrics();

        assertTrue(clickHouseService.queryForList("SELECT item_id FROM analytics_location_transit_events").isEmpty());
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
        locationService.createLocation(new LocationInput(id, id, 0.0, 0.0, null, null, LocationType.GENERIC, 100,
                true, false, Map.of()));
    }

    private void truncateMovementAnalyticsTables() throws Exception {
        clickHouseService.flushLocationTransitMetrics();
        try (Connection connection = clickHouseConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("TRUNCATE TABLE analytics_location_transit_events");
            statement.execute("TRUNCATE TABLE analytics_location_transit_counts");
            statement.execute("TRUNCATE TABLE analytics_path_transit_stats");
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
