package com.flunav.backend.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.flunav.backend.models.graph.GraphData;
import com.flunav.backend.models.response.ConveyorResponse;
import com.flunav.backend.models.response.ItemResponse;
import com.flunav.backend.models.response.LocationResponse;
import com.flunav.backend.test.ClickHouseTestContainerFactory;
import flunav.events.ItemCreatedEvent;
import flunav.types.ConveyorType;
import flunav.types.LocationType;
import flunav.types.PositionType;
import flunav.types.RoutingStatus;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.clickhouse.ClickHouseContainer;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClickHouseSnapshotTests {

    private static final ClickHouseContainer CLICKHOUSE = ClickHouseTestContainerFactory.create();

    private static ClickHouseService clickHouseService;

    @BeforeAll
    static void startClickHouse() {
        CLICKHOUSE.start();

        ObjectMapper objectMapper = JsonMapper.builder()
                .findAndAddModules()
                .build();

        clickHouseService = new ClickHouseService(
                String.format("http://%s:%d/default", CLICKHOUSE.getHost(), CLICKHOUSE.getMappedPort(8123)),
                CLICKHOUSE.getUsername(),
                CLICKHOUSE.getPassword(),
                objectMapper);
    }

    @AfterAll
    static void stopClickHouse() {
        if (clickHouseService != null) {
            clickHouseService.cleanup();
        }
        CLICKHOUSE.stop();
    }

    @Test
    void storesSnapshotFieldsAsTypedJsonPathsAndRestoresGraphData() throws Exception {
        Instant snapshotTimestamp = Instant.parse("2026-06-13T10:15:30.123Z");
        GraphData graphData = new GraphData(
                List.of(new LocationResponse(
                        "location-1",
                        "Entry",
                        LocationType.JUNCTION,
                        true,
                        42.5,
                        1.5,
                        10,
                        250L,
                        Map.of("enabled", true),
                        "#112233")),
                List.of(new ConveyorResponse(
                        "conveyor-1",
                        "location-1",
                        "location-2",
                        "Main belt",
                        25.0,
                        2.5,
                        0.75,
                        ConveyorType.BELT,
                        true,
                        true,
                        20,
                        Map.of("zone", "north"),
                        "#445566")),
                List.of(
                        new ItemResponse(
                                "item-1",
                                "Parcel",
                                true,
                                Map.of("weight", 12.5),
                                null,
                                "conveyor-1",
                                snapshotTimestamp.minusSeconds(5),
                                0.5,
                                List.of("parcel-destination"),
                                "location-2",
                                RoutingStatus.ASSIGNED,
                                snapshotTimestamp.minusSeconds(10),
                                null,
                                "#778899"),
                        new ItemResponse(
                                "item-2",
                                "Unrouted parcel",
                                true,
                                Map.of(),
                                "location-1",
                                null,
                                snapshotTimestamp,
                                0.0,
                                List.of(),
                                null,
                                RoutingStatus.UNROUTED,
                                snapshotTimestamp,
                                List.of(),
                                null)),
                snapshotTimestamp);

        graphData.getConveyors().getFirst().setFlowStopped(true);
        ItemResponse stagedItem = graphData.getItems().getFirst();
        stagedItem.setEffectivePriority(0.9);
        stagedItem.setRushActive(true);
        stagedItem.setPlannedPositionId("location-2");
        stagedItem.setPlannedPositionType(PositionType.LOCATION);
        stagedItem.setPlannedTransitionTimestamp(snapshotTimestamp.plusSeconds(2));
        stagedItem.setStagingOrder(3);
        stagedItem.setFlowPaused(true);
        stagedItem.setMovementCheckTimestamp(snapshotTimestamp.plusSeconds(1));

        clickHouseService.saveSnapshot(UUID.randomUUID().toString(), snapshotTimestamp, graphData);

        Optional<ClickHouseService.Snapshot> restored =
                clickHouseService.getMostRecentSnapshotBefore(snapshotTimestamp.plusSeconds(1));

        assertTrue(restored.isPresent());
        assertEquals(snapshotTimestamp, restored.orElseThrow().timestamp());
        assertEquals(graphData, restored.orElseThrow().graphData());

        try (Connection connection = DriverManager.getConnection(
                CLICKHOUSE.getJdbcUrl(),
                CLICKHOUSE.getUsername(),
                CLICKHOUSE.getPassword());
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("""
                        SELECT
                            toTypeName(graph_data.locations),
                            toTypeName(graph_data.conveyors),
                            toTypeName(graph_data.items),
                            toTypeName(graph_data.timestamp),
                            toTypeName(graph_data.items.entryTimestamp)
                        FROM snapshots
                        LIMIT 1
                        """)) {
            assertTrue(resultSet.next());
            assertTrue(resultSet.getString(1).startsWith("Array(Tuple("));
            assertTrue(resultSet.getString(2).startsWith("Array(Tuple("));
            assertTrue(resultSet.getString(3).startsWith("Array(Tuple("));
            assertTrue(resultSet.getString(3).contains("destinations Array(String)"));
            assertTrue(resultSet.getString(3).contains("selectedExitId Nullable(String)"));
            assertTrue(resultSet.getString(3).contains("routingStatus Nullable(String)"));
            assertTrue(resultSet.getString(3).contains("routingStatusUpdatedAt Nullable(String)"));
            assertEquals("String", resultSet.getString(4));
            assertTrue(resultSet.getString(5).contains("Nullable(String)"));
        }
    }

    @Test
    void snapshotSchemaUpgradeIsRepeatableAndPreservesExistingRows() throws Exception {
        String schema;
        try (var input = getClass().getResourceAsStream("/init-clickhouse/001_init_snapshot.sql")) {
            schema = new String(java.util.Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8)
                    .replace("default.snapshots", "default.snapshot_upgrade_test");
        }
        String legacyCreate = schema.substring(0, schema.indexOf(';'));
        for (String field : List.of("flowStopped", "effectivePriority", "rushActive", "plannedPositionId",
                "plannedPositionType", "plannedTransitionTimestamp", "stagingOrder", "flowPaused",
                "movementCheckTimestamp")) {
            legacyCreate = legacyCreate.replaceAll("(?m)^\\s*" + field + " [^\\n]+\\n", "");
        }
        legacyCreate = legacyCreate.replace("customBorderWidth Nullable(Float64),", "customBorderWidth Nullable(Float64)");
        try (Connection connection = DriverManager.getConnection(
                CLICKHOUSE.getJdbcUrl(), CLICKHOUSE.getUsername(), CLICKHOUSE.getPassword());
                Statement statement = connection.createStatement()) {
            try {
                statement.execute(legacyCreate);
                statement.execute("""
                        INSERT INTO snapshot_upgrade_test (snapshot_id, timestamp, graph_data)
                        VALUES (generateUUIDv4(), '2026-06-13 10:15:30.123',
                            '{"timestamp":"2026-06-13T10:15:30.123Z","items":[{"id":"legacy-item","priority":0.7}]}')
                        """);
                for (int attempt = 0; attempt < 2; attempt++) {
                    for (String sql : schema.split(";")) {
                        if (!sql.isBlank()) statement.execute(sql);
                    }
                }
                try (ResultSet result = statement.executeQuery("""
                        SELECT count(), any(graph_data.items.id)[1], any(graph_data.items.priority)[1]
                        FROM snapshot_upgrade_test
                        """)) {
                    assertTrue(result.next());
                    assertEquals(1, result.getLong(1));
                    assertEquals("legacy-item", result.getString(2));
                    assertEquals(0.7, result.getDouble(3));
                }
            } finally {
                statement.execute("DROP TABLE IF EXISTS snapshot_upgrade_test");
            }
        }
    }

    @Test
    void forwardPlaybackExcludesLateHistoricalRowsWhileRecoveryIncludesThem() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                CLICKHOUSE.getJdbcUrl(), CLICKHOUSE.getUsername(), CLICKHOUSE.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("TRUNCATE TABLE Events");
        }

        Instant recoveryStart = Instant.parse("2026-06-13T11:00:00Z");
        ItemCreatedEvent lateHistoricalEvent = new ItemCreatedEvent(
                "late-recovery-item", "Late", 1.0, true, "late-location",
                PositionType.LOCATION, 0.0, Map.of(), recoveryStart.minusSeconds(5));
        clickHouseService.saveEventAsync(lateHistoricalEvent);
        clickHouseService.flushAllEventsOrThrow();

        assertTrue(clickHouseService.getEventsBetween(
                recoveryStart, recoveryStart.plusSeconds(10)).isEmpty());
        assertEquals(List.of(lateHistoricalEvent.getEntityId()), clickHouseService.getEventsForRecoveryBetween(
                recoveryStart, recoveryStart.plusSeconds(10)).stream()
                .map(event -> ((flunav.events.EntityEvent) event).getEntityId())
                .toList());
    }

}
