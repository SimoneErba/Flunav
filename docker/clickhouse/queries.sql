-- Manual ClickHouse inspection queries.
-- Intended for ad hoc use with clickhouse-client.
-- This file is not part of automatic bootstrap under docker/clickhouse/init-clickhouse.

-- Schema discovery
SHOW TABLES FROM default LIKE 'analytics%';

SHOW TABLES FROM default LIKE '%journeys%';

SHOW TABLES FROM default LIKE 'ComponentMetrics';

DESCRIBE TABLE default.analytics_time_series;

DESCRIBE TABLE default.analytics_components;

DESCRIBE TABLE default.analytics_path_traversal_ingest;

DESCRIBE TABLE default.item_journeys;

DESCRIBE TABLE default.ComponentMetrics;

DESCRIBE TABLE default.analytics_location_transit_events;

DESCRIBE TABLE default.analytics_location_transit_counts;

DESCRIBE TABLE default.analytics_path_transit_stats;

-- analytics_time_series: recent throughput buckets
SELECT
    bucket_start,
    bucket_seconds,
    items_entered,
    items_exited,
    items_current
FROM default.analytics_time_series
ORDER BY bucket_start DESC, bucket_seconds DESC
LIMIT 100;

-- analytics_time_series: latest buckets for a specific resolution
SELECT
    bucket_start,
    items_entered,
    items_exited,
    items_current
FROM default.analytics_time_series
WHERE bucket_seconds = 60
ORDER BY bucket_start DESC
LIMIT 100;

-- analytics_components: busiest locations
SELECT
    simulation_id,
    location_id,
    sum(total_items_passed) AS total_items_passed,
    max(last_activity) AS last_activity
FROM default.analytics_components
WHERE simulation_id = 'live'
GROUP BY simulation_id, location_id
ORDER BY total_items_passed DESC, last_activity DESC
LIMIT 50;

-- analytics_components: most recently active locations
SELECT
    simulation_id,
    location_id,
    sum(total_items_passed) AS total_items_passed,
    max(last_activity) AS last_activity
FROM default.analytics_components
WHERE simulation_id = 'live'
GROUP BY simulation_id, location_id
ORDER BY last_activity DESC, total_items_passed DESC
LIMIT 50;

-- ComponentMetrics: recent metrics across all simulations
SELECT
    timestamp,
    simulation_id,
    component_id,
    component_type,
    metric_type,
    value
FROM default.ComponentMetrics
ORDER BY timestamp DESC, simulation_id, component_id
LIMIT 100;

-- ComponentMetrics: recent metrics for one simulation_id
SELECT
    timestamp,
    simulation_id,
    component_id,
    component_type,
    metric_type,
    value
FROM default.ComponentMetrics
WHERE simulation_id = 'live'
ORDER BY timestamp DESC, component_id, metric_type
LIMIT 100;

-- analytics_location_transit_events: raw recent transit facts
SELECT
    event_timestamp,
    simulation_id,
    item_id,
    from_location_id,
    to_location_id,
    from_position_id,
    from_position_type,
    to_position_id,
    to_position_type,
    transit_time_ms,
    path
FROM default.analytics_location_transit_events
WHERE simulation_id = 'live'
ORDER BY event_timestamp DESC, item_id
LIMIT 100;

-- analytics_location_transit_events: recent transits for one destination
SELECT
    event_timestamp,
    simulation_id,
    item_id,
    from_location_id,
    to_location_id,
    transit_time_ms,
    path
FROM default.analytics_location_transit_events
WHERE simulation_id = 'live'
  AND to_location_id = 'destination-id'
ORDER BY event_timestamp DESC, item_id
LIMIT 100;

-- analytics_location_transit_counts: counts per destination
SELECT
    simulation_id,
    location_id,
    sum(items_transited) AS items_transited,
    max(last_activity) AS last_activity
FROM default.analytics_location_transit_counts
WHERE simulation_id = 'live'
GROUP BY simulation_id, location_id
ORDER BY items_transited DESC, last_activity DESC
LIMIT 50;

-- analytics_location_transit_counts: latest active destinations
SELECT
    simulation_id,
    location_id,
    sum(items_transited) AS items_transited,
    max(last_activity) AS last_activity
FROM default.analytics_location_transit_counts
WHERE simulation_id = 'live'
GROUP BY simulation_id, location_id
ORDER BY last_activity DESC, items_transited DESC
LIMIT 50;

-- analytics_path_transit_stats withState: finalize AggregateFunction state for readable path metrics
SELECT
    simulation_id,
    from_location_id,
    to_location_id,
    countMerge(sample_count_state) AS sample_count,
    avgMerge(avg_transit_time_ms_state) AS avg_transit_time_ms,
    stddevPopMerge(stddev_transit_time_ms_state) AS stddev_transit_time_ms,
    quantileMerge(0.5)(median_transit_time_ms_state) AS median_transit_time_ms,
    min(min_transit_time_ms) AS min_transit_time_ms,
    max(max_transit_time_ms) AS max_transit_time_ms,
    max(last_activity) AS last_activity
FROM default.analytics_path_transit_stats
WHERE simulation_id = 'live'
GROUP BY simulation_id, from_location_id, to_location_id
ORDER BY sample_count DESC, last_activity DESC, from_location_id, to_location_id
LIMIT 50;

-- analytics_path_transit_stats withState: finalize state for one path pair
SELECT
    simulation_id,
    from_location_id,
    to_location_id,
    countMerge(sample_count_state) AS sample_count,
    avgMerge(avg_transit_time_ms_state) AS avg_transit_time_ms,
    stddevPopMerge(stddev_transit_time_ms_state) AS stddev_transit_time_ms,
    quantileMerge(0.5)(median_transit_time_ms_state) AS median_transit_time_ms,
    min(min_transit_time_ms) AS min_transit_time_ms,
    max(max_transit_time_ms) AS max_transit_time_ms,
    max(last_activity) AS last_activity
FROM default.analytics_path_transit_stats
WHERE simulation_id = 'live'
  AND from_location_id = 'source-id'
  AND to_location_id = 'destination-id'
GROUP BY simulation_id, from_location_id, to_location_id
ORDER BY last_activity DESC
LIMIT 10;

-- item_journeys withState: finalize AggregateFunction state for readable item paths
SELECT
    simulation_id,
    item_id,
    min(first_seen) AS first_seen,
    max(last_seen) AS last_seen,
    groupArrayArrayMerge(path_segments) AS path_segments
FROM default.item_journeys
WHERE simulation_id = 'live'
GROUP BY simulation_id, item_id
ORDER BY last_seen DESC, item_id
LIMIT 50;

-- item_journeys withState: inspect one item journey
SELECT
    simulation_id,
    item_id,
    min(first_seen) AS first_seen,
    max(last_seen) AS last_seen,
    groupArrayArrayMerge(path_segments) AS path_segments
FROM default.item_journeys
WHERE simulation_id = 'live'
  AND item_id = 'item-id'
GROUP BY simulation_id, item_id
ORDER BY last_seen DESC
LIMIT 1;
