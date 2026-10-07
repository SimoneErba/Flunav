CREATE TABLE IF NOT EXISTS flunav_schema_migrations
(
    migration_id String,
    applied_at DateTime64(3, 'UTC') DEFAULT now64(3)
)
ENGINE = ReplacingMergeTree(applied_at)
ORDER BY migration_id;

INSERT INTO analytics_component_metrics_1m
SELECT
    toStartOfMinute(timestamp),
    simulation_id,
    component_id,
    toString(component_type),
    avgStateIf(value, metric_type = 'OCCUPANCY'),
    minStateIf(value, metric_type = 'OCCUPANCY'),
    maxStateIf(value, metric_type = 'OCCUPANCY'),
    argMaxStateIf(value, timestamp, metric_type = 'OCCUPANCY'),
    avgStateIf(value, metric_type = 'SPEED'),
    minStateIf(value, metric_type = 'SPEED'),
    maxStateIf(value, metric_type = 'SPEED'),
    argMaxStateIf(value, timestamp, metric_type = 'SPEED'),
    argMaxStateIf(value, timestamp, metric_type = 'STATUS')
FROM ComponentMetrics
WHERE NOT EXISTS
(
    SELECT 1 FROM flunav_schema_migrations FINAL
    WHERE migration_id = '015_component_metrics_1m'
)
GROUP BY toStartOfMinute(timestamp), simulation_id, component_id, component_type;

INSERT INTO flunav_schema_migrations (migration_id)
SELECT '015_component_metrics_1m'
WHERE NOT EXISTS
(
    SELECT 1 FROM flunav_schema_migrations FINAL
    WHERE migration_id = '015_component_metrics_1m'
);

INSERT INTO analytics_location_transit_1m
SELECT
    toStartOfMinute(event_timestamp),
    simulation_id,
    from_location_id,
    to_location_id,
    countState(),
    uniqState(item_id),
    avgState(toFloat64(transit_time_ms)),
    maxState(transit_time_ms),
    quantileState(0.5)(toFloat64(transit_time_ms)),
    quantileState(0.95)(toFloat64(transit_time_ms))
FROM analytics_location_transit_events
WHERE NOT EXISTS
(
    SELECT 1 FROM flunav_schema_migrations FINAL
    WHERE migration_id = '015_location_transit_1m'
)
GROUP BY toStartOfMinute(event_timestamp), simulation_id, from_location_id, to_location_id;

INSERT INTO flunav_schema_migrations (migration_id)
SELECT '015_location_transit_1m'
WHERE NOT EXISTS
(
    SELECT 1 FROM flunav_schema_migrations FINAL
    WHERE migration_id = '015_location_transit_1m'
);

INSERT INTO analytics_destination_journeys_1m
SELECT
    toStartOfMinute(exit_timestamp),
    simulation_id,
    chute_id,
    countState(),
    avgState(toFloat64(traversal_time_ms)),
    quantileState(0.5)(toFloat64(traversal_time_ms)),
    quantileState(0.95)(toFloat64(traversal_time_ms)),
    maxState(traversal_time_ms)
FROM analytics_completed_journeys
WHERE NOT EXISTS
(
    SELECT 1 FROM flunav_schema_migrations FINAL
    WHERE migration_id = '015_destination_journeys_1m'
)
GROUP BY toStartOfMinute(exit_timestamp), simulation_id, chute_id;

INSERT INTO flunav_schema_migrations (migration_id)
SELECT '015_destination_journeys_1m'
WHERE NOT EXISTS
(
    SELECT 1 FROM flunav_schema_migrations FINAL
    WHERE migration_id = '015_destination_journeys_1m'
);
