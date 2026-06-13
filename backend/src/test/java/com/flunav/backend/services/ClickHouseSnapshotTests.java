package com.flunav.backend.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.flunav.backend.models.graph.GraphData;
import com.flunav.backend.models.response.ConveyorResponse;
import com.flunav.backend.models.response.ItemResponse;
import com.flunav.backend.models.response.LocationResponse;
import flunav.types.ConveyorType;
import flunav.types.LocationType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.clickhouse.ClickHouseContainer;

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

    private static final ClickHouseContainer CLICKHOUSE = new ClickHouseContainer(
            "clickhouse/clickhouse-server:25.3")
            .withInitScript("init-clickhouse/001_init_snapshot.sql");

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
                        Map.of("priority", 3, "enabled", true),
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
                                "location-2",
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
                                null,
                                List.of(),
                                null)),
                snapshotTimestamp);

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
            assertEquals("String", resultSet.getString(4));
            assertTrue(resultSet.getString(5).contains("Nullable(String)"));
        }
    }
}
