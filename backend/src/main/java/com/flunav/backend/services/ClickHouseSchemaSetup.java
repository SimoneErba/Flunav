package com.flunav.backend.services;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.query.QueryResponse;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.flunav.backend.services.ClickHouseService.*;
/** Runs the existing idempotent schema setup with the facade client. */
final class ClickHouseSchemaSetup extends ClickHouseAccess {
    private static final Logger logger = LoggerFactory.getLogger(ClickHouseSchemaSetup.class);

    ClickHouseSchemaSetup(Client client, ObjectMapper mapper, String database) {
        super(client, mapper, database);
    }

    void ensureAnalyticsSchema() {
        try {
            String columnsSql = """
                    SELECT name, type
                    FROM system.columns
                    WHERE database = {database:String}
                      AND table = 'analytics_time_series'
                    FORMAT JSONEachRow
                    """;
            Map<String, String> columns = new HashMap<>();
            try (QueryResponse response = client.query(columnsSql, Map.of("database", clickhouseDatabase)).get();
                    InputStream inputStream = response.getInputStream()) {
                MappingIterator<Map<String, Object>> rows = objectMapper.readerFor(Map.class).readValues(inputStream);
                while (rows.hasNext()) {
                    Map<String, Object> row = rows.next();
                    columns.put(String.valueOf(row.get("name")), String.valueOf(row.get("type")));
                }
            }

            boolean valid = columns.size() == 5
                    && "DateTime64(3)".equals(columns.get("bucket_start"))
                    && "UInt16".equals(columns.get("bucket_seconds"))
                    && "UInt64".equals(columns.get("items_entered"))
                    && "UInt64".equals(columns.get("items_exited"))
                    && "UInt64".equals(columns.get("items_current"));

            if (!columns.isEmpty() && !valid) {
                String archive = "analytics_time_series_legacy_" + Instant.now().toEpochMilli();
                executeClickHouseStatement("RENAME TABLE " + clickhouseDatabase + ".analytics_time_series TO "
                        + clickhouseDatabase + "." + archive);
                logger.warn("Archived incompatible analytics table as {}.{}", clickhouseDatabase, archive);
            }

            if (!valid) {
                executeClickHouseStatement("""
                        CREATE TABLE IF NOT EXISTS %s.analytics_time_series
                        (
                            bucket_start DateTime64(3),
                            bucket_seconds UInt16,
                            items_entered UInt64,
                            items_exited UInt64,
                            items_current UInt64
                        )
                        ENGINE = MergeTree()
                        PARTITION BY toYYYYMM(bucket_start)
                        ORDER BY (bucket_start, bucket_seconds)
                        """.formatted(clickhouseDatabase));
            }
        } catch (Exception e) {
            throw new IllegalStateException("Unable to initialize ClickHouse analytics schema", e);
        }
    }

    void ensureMovementAnalyticsSchema() {
        try {
            dropAnalyticsMaterializedViews();
            archiveTableIfMissingColumns("analytics_path_traversal_ingest", List.of("simulation_id"));
            archiveTableIfMissingColumns("item_journeys", List.of("simulation_id"));
            archiveTableIfMissingColumns("analytics_components", List.of("simulation_id"));
            archiveTableIfMissingColumns("analytics_location_transit_events", List.of("simulation_id"));
            archiveTableIfMissingColumns("analytics_location_transit_counts", List.of("simulation_id"));
            archiveTableIfMissingColumns("analytics_path_transit_stats", List.of("simulation_id"));

            executeClickHouseStatement("""
                    CREATE TABLE IF NOT EXISTS %s.analytics_path_traversal_ingest
                    (
                        event_timestamp DateTime64(3),
                        source_event_id String,
                        simulation_id LowCardinality(String),
                        item_id String,
                        previous_position_id String,
                        previous_position_type LowCardinality(String),
                        new_position_id String,
                        new_position_type LowCardinality(String),
                        path Array(String)
                    )
                    ENGINE = Null
                    """.formatted(clickhouseDatabase));
            executeClickHouseStatement("ALTER TABLE " + clickhouseDatabase
                    + ".analytics_location_transit_events ADD COLUMN IF NOT EXISTS source_event_id String DEFAULT '' AFTER event_timestamp");

            executeClickHouseStatement("""
                    CREATE TABLE IF NOT EXISTS %s.item_journeys
                    (
                        simulation_id LowCardinality(String),
                        item_id String,
                        first_seen SimpleAggregateFunction(min, DateTime64(3)),
                        last_seen SimpleAggregateFunction(max, DateTime64(3)),
                        path_segments AggregateFunction(groupArrayArray, Array(String))
                    )
                    ENGINE = AggregatingMergeTree()
                    ORDER BY (simulation_id, item_id)
                    """.formatted(clickhouseDatabase));

            executeClickHouseStatement("""
                    CREATE MATERIALIZED VIEW IF NOT EXISTS %s.mv_item_journeys
                    TO %s.item_journeys
                    AS
                    SELECT
                        simulation_id,
                        item_id,
                        min(event_timestamp) AS first_seen,
                        max(event_timestamp) AS last_seen,
                        groupArrayArrayState(path) AS path_segments
                    FROM %s.analytics_path_traversal_ingest
                    GROUP BY simulation_id, item_id
                    """.formatted(clickhouseDatabase, clickhouseDatabase, clickhouseDatabase));

            executeClickHouseStatement("""
                    CREATE TABLE IF NOT EXISTS %s.analytics_components
                    (
                        simulation_id LowCardinality(String),
                        location_id LowCardinality(String),
                        total_items_passed UInt64,
                        last_activity SimpleAggregateFunction(max, DateTime64(3))
                    )
                    ENGINE = SummingMergeTree()
                    ORDER BY (simulation_id, location_id)
                    """.formatted(clickhouseDatabase));

            executeClickHouseStatement("""
                    CREATE MATERIALIZED VIEW IF NOT EXISTS %s.mv_analytics_components
                    TO %s.analytics_components
                    AS
                    SELECT
                        simulation_id,
                        arrayJoin(path) AS location_id,
                        count() AS total_items_passed,
                        max(event_timestamp) AS last_activity
                    FROM %s.analytics_path_traversal_ingest
                    GROUP BY simulation_id, location_id
                    """.formatted(clickhouseDatabase, clickhouseDatabase, clickhouseDatabase));

            executeClickHouseStatement("""
                    CREATE TABLE IF NOT EXISTS %s.analytics_location_transit_events
                    (
                        event_timestamp DateTime64(3),
                        simulation_id LowCardinality(String),
                        item_id String,
                        from_location_id LowCardinality(String),
                        to_location_id LowCardinality(String),
                        from_position_id String,
                        from_position_type LowCardinality(String),
                        to_position_id String,
                        to_position_type LowCardinality(String),
                        transit_time_ms UInt64,
                        path Array(String)
                    )
                    ENGINE = MergeTree()
                    PARTITION BY toYYYYMM(event_timestamp)
                    ORDER BY (simulation_id, from_location_id, to_location_id, event_timestamp, item_id)
                    """.formatted(clickhouseDatabase));

            executeClickHouseStatement("""
                    CREATE TABLE IF NOT EXISTS %s.analytics_location_transit_counts
                    (
                        simulation_id LowCardinality(String),
                        location_id LowCardinality(String),
                        items_transited UInt64,
                        last_activity SimpleAggregateFunction(max, DateTime64(3))
                    )
                    ENGINE = SummingMergeTree()
                    ORDER BY (simulation_id, location_id)
                    """.formatted(clickhouseDatabase));

            executeClickHouseStatement("""
                    CREATE MATERIALIZED VIEW IF NOT EXISTS %s.mv_analytics_location_transit_counts
                    TO %s.analytics_location_transit_counts
                    AS
                    SELECT
                        simulation_id,
                        to_location_id AS location_id,
                        count() AS items_transited,
                        max(event_timestamp) AS last_activity
                    FROM %s.analytics_location_transit_events
                    GROUP BY simulation_id, location_id
                    """.formatted(clickhouseDatabase, clickhouseDatabase, clickhouseDatabase));

            executeClickHouseStatement("""
                    CREATE TABLE IF NOT EXISTS %s.analytics_path_transit_stats
                    (
                        simulation_id LowCardinality(String),
                        from_location_id LowCardinality(String),
                        to_location_id LowCardinality(String),
                        sample_count_state AggregateFunction(count),
                        avg_transit_time_ms_state AggregateFunction(avg, Float64),
                        stddev_transit_time_ms_state AggregateFunction(stddevPop, Float64),
                        median_transit_time_ms_state AggregateFunction(quantile(0.5), Float64),
                        min_transit_time_ms SimpleAggregateFunction(min, UInt64),
                        max_transit_time_ms SimpleAggregateFunction(max, UInt64),
                        last_activity SimpleAggregateFunction(max, DateTime64(3))
                    )
                    ENGINE = AggregatingMergeTree()
                    ORDER BY (simulation_id, from_location_id, to_location_id)
                    """.formatted(clickhouseDatabase));

            executeClickHouseStatement("""
                    CREATE MATERIALIZED VIEW IF NOT EXISTS %s.mv_analytics_path_transit_stats
                    TO %s.analytics_path_transit_stats
                    AS
                    SELECT
                        simulation_id,
                        from_location_id,
                        to_location_id,
                        countState() AS sample_count_state,
                        avgState(toFloat64(transit_time_ms)) AS avg_transit_time_ms_state,
                        stddevPopState(toFloat64(transit_time_ms)) AS stddev_transit_time_ms_state,
                        quantileState(0.5)(toFloat64(transit_time_ms)) AS median_transit_time_ms_state,
                        min(transit_time_ms) AS min_transit_time_ms,
                        max(transit_time_ms) AS max_transit_time_ms,
                        max(event_timestamp) AS last_activity
                    FROM %s.analytics_location_transit_events
                    GROUP BY simulation_id, from_location_id, to_location_id
                    """.formatted(clickhouseDatabase, clickhouseDatabase, clickhouseDatabase));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to initialize ClickHouse movement analytics schema", e);
        }
    }

    void ensureOperationalAnalyticsSchema() {
        try {
            executeClickHouseStatement("""
                    CREATE TABLE IF NOT EXISTS analytics_exit_candidates
                    (
                        candidate_id String,
                        exit_event_id String,
                        item_id String,
                        chute_id String,
                        exit_timestamp DateTime64(3, 'UTC'),
                        simulation_id LowCardinality(String),
                        simulation_created_at Nullable(DateTime64(3, 'UTC')),
                        live_history_cutoff Nullable(DateTime64(3, 'UTC')),
                        version DateTime64(3, 'UTC') DEFAULT now64(3)
                    ) ENGINE = ReplacingMergeTree(version)
                    ORDER BY (simulation_id, candidate_id)
                    """);
            executeClickHouseStatement("""
                    CREATE TABLE IF NOT EXISTS analytics_completed_journeys
                    (
                        journey_id String,
                        item_id String,
                        chute_id String,
                        created_timestamp DateTime64(3, 'UTC'),
                        exit_timestamp DateTime64(3, 'UTC'),
                        traversal_time_ms UInt64,
                        simulation_id LowCardinality(String),
                        version DateTime64(3, 'UTC') DEFAULT now64(3)
                    ) ENGINE = ReplacingMergeTree(version)
                    ORDER BY (simulation_id, journey_id)
                    """);
            executeClickHouseStatement("""
                    CREATE TABLE IF NOT EXISTS analytics_recirculation_facts
                    (
                        recirculation_id String,
                        event_timestamp DateTime64(3, 'UTC'),
                        simulation_id LowCardinality(String),
                        item_id String,
                        previous_path Array(String),
                        new_path Array(String),
                        version DateTime64(3, 'UTC') DEFAULT now64(3)
                    ) ENGINE = ReplacingMergeTree(version)
                    ORDER BY (simulation_id, recirculation_id)
                    """);
            executeClickHouseStatement("""
                    CREATE TABLE IF NOT EXISTS analytics_simulation_connection_events
                    (
                        event_id String,
                        event_timestamp DateTime64(3, 'UTC'),
                        simulation_id LowCardinality(String),
                        conveyor_id String,
                        event_type LowCardinality(String),
                        active Nullable(UInt8),
                        speed Nullable(Float64),
                        source_id String,
                        target_id String,
                        version DateTime64(3, 'UTC') DEFAULT now64(3)
                    ) ENGINE = ReplacingMergeTree(version)
                    ORDER BY (simulation_id, event_id)
                    """);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to ensure operational analytics schema", e);
        }
    }

    void ensureAlarmAnalyticsSchema() {
        try {
            executeClickHouseStatement("""
                    CREATE TABLE IF NOT EXISTS analytics_alarm_events
                    (
                        event_id String,
                        alarm_id String,
                        conveyor_id String,
                        event_type LowCardinality(String),
                        severity LowCardinality(String),
                        typology String,
                        stops_conveyor UInt8,
                        event_timestamp DateTime64(3, 'UTC'),
                        simulation_id LowCardinality(String),
                        version DateTime64(3, 'UTC') DEFAULT now64(3)
                    ) ENGINE = ReplacingMergeTree(version)
                    ORDER BY (simulation_id, event_id)
                    """);
            executeClickHouseStatement("""
                    CREATE TABLE IF NOT EXISTS analytics_alarm_affected_items
                    (
                        event_id String,
                        alarm_id String,
                        conveyor_id String,
                        item_id String,
                        captured_at DateTime64(3, 'UTC'),
                        simulation_id LowCardinality(String),
                        version DateTime64(3, 'UTC') DEFAULT now64(3)
                    ) ENGINE = ReplacingMergeTree(version)
                    ORDER BY (simulation_id, alarm_id, item_id)
                    """);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to ensure alarm analytics schema", e);
        }
    }

    void ensureAnomalyAnalyticsSchema() {
        try {
            executeClickHouseStatement("""
                    CREATE TABLE IF NOT EXISTS analytics_anomaly_findings
                    (
                        finding_id String, scope_id LowCardinality(String), simulation_id LowCardinality(String),
                        detector LowCardinality(String), detector_version LowCardinality(String),
                        temporal_mode LowCardinality(String), component_id String,
                        component_type LowCardinality(String), item_id String, previous_position_id String,
                        reported_position_id String, expected_intermediate_positions Array(String),
                        observed_metrics_json String, baseline_mean Nullable(Float64),
                        baseline_median Nullable(Float64), baseline_stddev Nullable(Float64),
                        baseline_mad Nullable(Float64), z_score Nullable(Float64), modified_z_score Nullable(Float64),
                        sample_count UInt64, baseline_window_start Nullable(DateTime64(3, 'UTC')),
                        baseline_window_end Nullable(DateTime64(3, 'UTC')), severity LowCardinality(String),
                        alarm_id String, alarm_state LowCardinality(String),
                        observation_timestamp DateTime64(3, 'UTC'), tick_timestamp DateTime64(3, 'UTC'),
                        version DateTime64(3, 'UTC') DEFAULT now64(3)
                    ) ENGINE = ReplacingMergeTree(version) ORDER BY (scope_id, finding_id)
                    """);
            executeClickHouseStatement("""
                    CREATE TABLE IF NOT EXISTS analytics_anomaly_incidents
                    (
                        incident_id String, scope_id LowCardinality(String), simulation_id LowCardinality(String),
                        probable_root_component_id String, confidence LowCardinality(String),
                        finding_ids Array(String), component_ids Array(String),
                        first_finding_timestamp DateTime64(3, 'UTC'), updated_at DateTime64(3, 'UTC'),
                        version DateTime64(3, 'UTC') DEFAULT now64(3)
                    ) ENGINE = ReplacingMergeTree(version) ORDER BY (scope_id, incident_id)
                    """);
            executeClickHouseStatement("""
                    CREATE TABLE IF NOT EXISTS analytics_detector_baselines
                    (
                        baseline_id String, scope_id LowCardinality(String), detector_version LowCardinality(String),
                        detector LowCardinality(String), component_id String, path_key String,
                        window_start DateTime64(3, 'UTC'), window_end DateTime64(3, 'UTC'), sample_count UInt64,
                        mean Float64, population_stddev Float64, median Float64, mad Float64,
                        minimum Float64, maximum Float64, calculated_at DateTime64(3, 'UTC'),
                        epoch_started_at DateTime64(3, 'UTC'), version DateTime64(3, 'UTC') DEFAULT now64(3)
                    ) ENGINE = ReplacingMergeTree(version)
                    ORDER BY (scope_id, detector, path_key, baseline_id)
                    """);
            executeClickHouseStatement("""
                    CREATE TABLE IF NOT EXISTS analytics_component_flow_events
                    (
                        source_event_id String, event_timestamp DateTime64(3, 'UTC'),
                        scope_id LowCardinality(String), simulation_id LowCardinality(String), component_id String,
                        component_type LowCardinality(String), direction LowCardinality(String), item_id String,
                        version DateTime64(3, 'UTC') DEFAULT now64(3)
                    ) ENGINE = ReplacingMergeTree(version)
                    PARTITION BY toYYYYMM(event_timestamp)
                    ORDER BY (scope_id, source_event_id, component_id, direction)
                    """);
            executeClickHouseStatement("""
                    CREATE TABLE IF NOT EXISTS analytics_component_flow_1m
                    (
                        bucket_start DateTime64(3, 'UTC'), scope_id LowCardinality(String),
                        simulation_id LowCardinality(String), component_id String,
                        component_type LowCardinality(String), arrivals UInt64, departures UInt64,
                        throughput UInt64, pressure Int64,
                        version DateTime64(3, 'UTC') DEFAULT now64(3)
                    ) ENGINE = ReplacingMergeTree(version)
                    PARTITION BY toYYYYMM(bucket_start)
                    ORDER BY (scope_id, component_type, component_id, bucket_start)
                    """);
            executeClickHouseStatement("ALTER TABLE analytics_alarm_events ADD COLUMN IF NOT EXISTS finding_id String DEFAULT '' AFTER alarm_id");
            executeClickHouseStatement("ALTER TABLE analytics_alarm_events ADD COLUMN IF NOT EXISTS component_type LowCardinality(String) DEFAULT 'CONVEYOR' AFTER conveyor_id");
            executeClickHouseStatement("ALTER TABLE analytics_alarm_events ADD COLUMN IF NOT EXISTS source LowCardinality(String) DEFAULT 'MANUAL' AFTER typology");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to ensure anomaly analytics schema", e);
        }
    }

    private void dropAnalyticsMaterializedViews() throws Exception {
        executeClickHouseStatement("DROP TABLE IF EXISTS " + clickhouseDatabase + ".mv_item_journeys");
        executeClickHouseStatement("DROP TABLE IF EXISTS " + clickhouseDatabase + ".mv_analytics_components");
        executeClickHouseStatement("DROP TABLE IF EXISTS " + clickhouseDatabase + ".mv_analytics_location_transit_counts");
        executeClickHouseStatement("DROP TABLE IF EXISTS " + clickhouseDatabase + ".mv_analytics_path_transit_stats");
    }

    private void archiveTableIfMissingColumns(String tableName, List<String> requiredColumns) throws Exception {
        Map<String, String> columns = tableColumns(tableName);
        if (columns.isEmpty()) {
            return;
        }

        boolean compatible = requiredColumns.stream().allMatch(columns::containsKey);
        if (compatible) {
            return;
        }

        String archive = tableName + "_legacy_" + Instant.now().toEpochMilli();
        executeClickHouseStatement("RENAME TABLE " + clickhouseDatabase + "." + tableName + " TO "
                + clickhouseDatabase + "." + archive);
        logger.warn("Archived incompatible ClickHouse analytics table as {}.{}", clickhouseDatabase, archive);
    }

    void ensureMultiSimulationSchema() throws Exception {
        executeClickHouseStatement("""
                CREATE TABLE IF NOT EXISTS %s.multi_simulations
                (
                    experiment_id String,
                    name String,
                    status LowCardinality(String),
                    configuration_json String,
                    baseline_json String,
                    topology_version String,
                    configuration_version String,
                    base_seed Int64,
                    total_runs UInt32,
                    completed_runs UInt32,
                    failed_runs UInt32,
                    created_at DateTime64(3, 'UTC'),
                    started_at Nullable(DateTime64(3, 'UTC')),
                    completed_at Nullable(DateTime64(3, 'UTC')),
                    cancel_requested UInt8,
                    error String,
                    version UInt64
                )
                ENGINE = ReplacingMergeTree(version)
                ORDER BY experiment_id
                """.formatted(clickhouseDatabase));
        executeClickHouseStatement("""
                CREATE TABLE IF NOT EXISTS %s.multi_simulation_runs
                (
                    experiment_id String,
                    run_index UInt32,
                    seed Int64,
                    status LowCardinality(String),
                    effective_arrival_rate Float64,
                    started_at Nullable(DateTime64(3, 'UTC')),
                    completed_at Nullable(DateTime64(3, 'UTC')),
                    metrics_json String,
                    error String,
                    version UInt64
                )
                ENGINE = ReplacingMergeTree(version)
                ORDER BY (experiment_id, run_index)
                """.formatted(clickhouseDatabase));
        executeClickHouseStatement("""
                CREATE TABLE IF NOT EXISTS %s.multi_simulation_reports
                (
                    experiment_id String,
                    report_json String,
                    generated_at DateTime64(3, 'UTC'),
                    version UInt64
                )
                ENGINE = ReplacingMergeTree(version)
                ORDER BY experiment_id
                """.formatted(clickhouseDatabase));
    }
}
