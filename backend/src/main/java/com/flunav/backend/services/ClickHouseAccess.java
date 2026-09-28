package com.flunav.backend.services;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.query.QueryResponse;
import com.clickhouse.data.ClickHouseFormat;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;

import com.flunav.backend.services.ClickHouseService.*;
/** Shared client, JSON encoding, and query conversion; no queue or scheduler ownership. */
class ClickHouseAccess {
    private static final Logger logger = LoggerFactory.getLogger(ClickHouseAccess.class);
    protected final Client client;
    protected final ObjectMapper objectMapper;
    protected final String clickhouseDatabase;
    protected static final DateTimeFormatter CLICKHOUSE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
            .withZone(ZoneOffset.UTC);
    protected static final int MAX_QUEUE_SIZE = 100_000;
    protected static final int BATCH_SIZE = 1000;

    ClickHouseAccess(Client client, ObjectMapper objectMapper, String database) {
        this.client = client;
        this.objectMapper = objectMapper;
        this.clickhouseDatabase = database;
    }


    protected String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encode analytics JSON", e);
        }
    }

    protected String formatNullable(Instant value) {
        return value != null ? CLICKHOUSE_FORMATTER.format(value) : null;
    }

    protected void insertJsonRows(String table, List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return;
        }
        try {
            StringBuilder body = new StringBuilder();
            for (Map<String, Object> row : rows) {
                body.append(objectMapper.writeValueAsString(row)).append('\n');
            }
            try (InputStream input = new ByteArrayInputStream(body.toString().getBytes(StandardCharsets.UTF_8))) {
                client.insert(table, input, ClickHouseFormat.JSONEachRow).get();
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to write " + table, e);
        }
    }

    protected void executeClickHouseStatement(String sql) throws Exception {
        try (QueryResponse ignored = client.query(sql).get()) {
        }
    }

    protected void insertJsonRow(String table, Map<String, Object> row) throws Exception {
        byte[] json = objectMapper.writeValueAsBytes(row);
        try (InputStream input = new ByteArrayInputStream(json)) {
            client.insert(table, input, ClickHouseFormat.JSONEachRow).get();
        }
    }

    protected List<Map<String, Object>> queryRows(String sql, Map<String, Object> parameters) {
        try (QueryResponse response = client.query(sql, parameters).get();
                InputStream input = response.getInputStream()) {
            List<Map<String, Object>> rows = new ArrayList<>();
            MappingIterator<Map<String, Object>> iterator = objectMapper.readerFor(Map.class).readValues(input);
            while (iterator.hasNext()) {
                rows.add(iterator.next());
            }
            return rows;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to query multi-simulation data", e);
        }
    }

    protected String blankToNull(Object raw) {
        String value = Objects.toString(raw, "");
        return value.isBlank() ? null : value;
    }

    protected Map<String, String> tableColumns(String tableName) throws Exception {
        String columnsSql = """
                SELECT name, type
                FROM system.columns
                WHERE database = {database:String}
                  AND table = {table:String}
                FORMAT JSONEachRow
                """;
        Map<String, String> columns = new HashMap<>();
        try (QueryResponse response = client.query(columnsSql, Map.of(
                "database", clickhouseDatabase,
                "table", tableName)).get();
                InputStream inputStream = response.getInputStream()) {
            MappingIterator<Map<String, Object>> rows = objectMapper.readerFor(Map.class).readValues(inputStream);
            while (rows.hasNext()) {
                Map<String, Object> row = rows.next();
                columns.put(String.valueOf(row.get("name")), String.valueOf(row.get("type")));
            }
        }
        return columns;
    }

    protected static String extractDatabase(String clickhouseUrl) {
        try {
            String path = URI.create(clickhouseUrl).getPath();
            String database = path == null || path.isBlank() || "/".equals(path)
                    ? "default"
                    : path.substring(path.lastIndexOf('/') + 1);
            if (!database.matches("[A-Za-z0-9_]+")) {
                throw new IllegalArgumentException("Invalid ClickHouse database name");
            }
            return database;
        } catch (Exception e) {
            throw new IllegalArgumentException("ClickHouse URL must identify a valid database", e);
        }
    }

    public List<Map<String, Object>> queryForList(String sql) {
        // Ensure we request JSON format so Jackson can parse it
        String finalSql = sql.trim();
        if (!finalSql.toUpperCase().endsWith("FORMAT JSONEACHROW")) {
            finalSql += " FORMAT JSONEachRow";
        }

        logger.debug("Executing generic query: {}", finalSql);

        List<Map<String, Object>> results = new ArrayList<>();

        try (QueryResponse response = client.query(finalSql).get()) {
            try (InputStream inputStream = response.getInputStream()) {
                // Use Jackson to read the stream of JSON objects (NDJSON)
                MappingIterator<Map<String, Object>> it = objectMapper
                        .readerFor(Map.class)
                        .readValues(inputStream);

                while (it.hasNext()) {
                    results.add(it.next());
                }
            }
            return results;
        } catch (Exception e) {
            logger.error("Failed to execute generic query: {}", finalSql, e);
            throw new RuntimeException("Generic query failed", e);
        }
    }

    protected boolean asBoolean(Object value) {
        return value instanceof Boolean bool ? bool : Integer.parseInt(String.valueOf(value)) != 0;
    }

    protected Instant parseClickHouseInstant(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        if (value instanceof String text) {
            try {
                return Instant.parse(text);
            } catch (Exception ignored) {
                LocalDateTime localDateTime = LocalDateTime.parse(text, CLICKHOUSE_FORMATTER);
                return localDateTime.toInstant(ZoneOffset.UTC);
            }
        }
        throw new IllegalArgumentException("Unsupported ClickHouse timestamp value: " + value);
    }

    protected Instant parseNullableInstant(Object value) {
        if (value == null || "null".equalsIgnoreCase(String.valueOf(value))) {
            return null;
        }
        return parseClickHouseInstant(value);
    }

    protected Map<String, Object> asPayload(Object value) {
        if (value instanceof Map<?, ?> map) {
            return new LinkedHashMap<>((Map<String, Object>) map);
        }
        if (value == null) {
            return Map.of();
        }
        return Map.of("value", value);
    }

    protected long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    protected double asDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return Double.parseDouble(String.valueOf(value));
    }

    protected Double asNullableDouble(Object value) {
        return value == null ? null : asDouble(value);
    }

    protected Boolean asNullableBoolean(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    public void execute(String sql) {
        logger.debug("Executing raw SQL: {}", sql);
        try {
            // .get() waits for the future to complete, ensuring the query ran
            client.query(sql).get().close();
        } catch (Exception e) {
            logger.error("Failed to execute SQL: {}", sql, e);
            throw new RuntimeException("Raw SQL execution failed", e);
        }
    }

    protected <T> void enqueueOrThrow(BlockingQueue<T> queue, T value, String queueName) {
        if (!queue.offer(value)) {
            throw new IllegalStateException("ClickHouse " + queueName + " queue reached capacity " + MAX_QUEUE_SIZE);
        }
    }
}
