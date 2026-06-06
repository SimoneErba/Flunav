package com.flunav.backend;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import org.junit.jupiter.api.Test;

class LogsSchemaTests extends BaseIntegrationTest {

    @Test
    void logsTableUsesExpectedOrderingPartitionAndRetention() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                CLICKHOUSE_CONTAINER.getJdbcUrl(),
                CLICKHOUSE_CONTAINER.getUsername(),
                CLICKHOUSE_CONTAINER.getPassword());
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SHOW CREATE TABLE default.logs")) {
            assertTrue(result.next());
            String ddl = result.getString(1);

            assertTrue(ddl.contains("DateTime64(3, 'UTC')"));
            assertTrue(ddl.contains("MATERIALIZED toDate(timestamp)"));
            assertTrue(ddl.contains("PARTITION BY toYYYYMM(timestamp)"));
            assertTrue(ddl.contains("ORDER BY (simulation_id, logger, timestamp)"));
            assertTrue(ddl.contains("TTL toDateTime(timestamp) + toIntervalDay(30)"));
        }
    }
}
