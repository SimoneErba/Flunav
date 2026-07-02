CREATE TABLE IF NOT EXISTS default.analytics_location_transit_events
(
    `event_timestamp` DateTime64(3),
    `item_id` String,
    `from_location_id` LowCardinality(String),
    `to_location_id` LowCardinality(String),
    `from_position_id` String,
    `from_position_type` LowCardinality(String),
    `to_position_id` String,
    `to_position_type` LowCardinality(String),
    `transit_time_ms` UInt64,
    `path` Array(String)
)
ENGINE = MergeTree()
PARTITION BY toYYYYMM(event_timestamp)
ORDER BY (from_location_id, to_location_id, event_timestamp, item_id);

CREATE TABLE IF NOT EXISTS default.analytics_location_transit_counts
(
    `location_id` LowCardinality(String),
    `items_transited` UInt64,
    `last_activity` SimpleAggregateFunction(max, DateTime64(3))
)
ENGINE = SummingMergeTree()
ORDER BY location_id;

CREATE MATERIALIZED VIEW IF NOT EXISTS default.mv_analytics_location_transit_counts
TO default.analytics_location_transit_counts
AS
SELECT
    to_location_id AS location_id,
    count() AS items_transited,
    max(event_timestamp) AS last_activity
FROM default.analytics_location_transit_events
GROUP BY location_id;

CREATE TABLE IF NOT EXISTS default.analytics_path_transit_stats
(
    `from_location_id` LowCardinality(String),
    `to_location_id` LowCardinality(String),
    `sample_count_state` AggregateFunction(count),
    `avg_transit_time_ms_state` AggregateFunction(avg, Float64),
    `stddev_transit_time_ms_state` AggregateFunction(stddevPop, Float64),
    `median_transit_time_ms_state` AggregateFunction(quantile(0.5), Float64),
    `min_transit_time_ms` SimpleAggregateFunction(min, UInt64),
    `max_transit_time_ms` SimpleAggregateFunction(max, UInt64),
    `last_activity` SimpleAggregateFunction(max, DateTime64(3))
)
ENGINE = AggregatingMergeTree()
ORDER BY (from_location_id, to_location_id);

CREATE MATERIALIZED VIEW IF NOT EXISTS default.mv_analytics_path_transit_stats
TO default.analytics_path_transit_stats
AS
SELECT
    from_location_id,
    to_location_id,
    countState() AS sample_count_state,
    avgState(toFloat64(transit_time_ms)) AS avg_transit_time_ms_state,
    stddevPopState(toFloat64(transit_time_ms)) AS stddev_transit_time_ms_state,
    quantileState(0.5)(toFloat64(transit_time_ms)) AS median_transit_time_ms_state,
    min(transit_time_ms) AS min_transit_time_ms,
    max(transit_time_ms) AS max_transit_time_ms,
    max(event_timestamp) AS last_activity
FROM default.analytics_location_transit_events
GROUP BY from_location_id, to_location_id;
