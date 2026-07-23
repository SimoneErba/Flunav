CREATE TABLE IF NOT EXISTS analytics_component_metrics_1m
(
    bucket_start DateTime('UTC'),
    simulation_id LowCardinality(String),
    component_id String,
    component_type LowCardinality(String),
    occupancy_avg_state AggregateFunction(avg, Float64),
    occupancy_min_state AggregateFunction(min, Float64),
    occupancy_max_state AggregateFunction(max, Float64),
    occupancy_latest_state AggregateFunction(argMax, Float64, DateTime64(3)),
    speed_avg_state AggregateFunction(avg, Float64),
    speed_min_state AggregateFunction(min, Float64),
    speed_max_state AggregateFunction(max, Float64),
    speed_latest_state AggregateFunction(argMax, Float64, DateTime64(3)),
    status_latest_state AggregateFunction(argMax, Float64, DateTime64(3))
) ENGINE = AggregatingMergeTree ORDER BY (simulation_id, component_id, bucket_start);

CREATE MATERIALIZED VIEW IF NOT EXISTS mv_analytics_component_metrics_1m
TO analytics_component_metrics_1m AS
SELECT toStartOfMinute(timestamp) bucket_start, simulation_id, component_id,
    toString(component_type) component_type,
    avgStateIf(value, metric_type = 'OCCUPANCY') occupancy_avg_state,
    minStateIf(value, metric_type = 'OCCUPANCY') occupancy_min_state,
    maxStateIf(value, metric_type = 'OCCUPANCY') occupancy_max_state,
    argMaxStateIf(value, timestamp, metric_type = 'OCCUPANCY') occupancy_latest_state,
    avgStateIf(value, metric_type = 'SPEED') speed_avg_state,
    minStateIf(value, metric_type = 'SPEED') speed_min_state,
    maxStateIf(value, metric_type = 'SPEED') speed_max_state,
    argMaxStateIf(value, timestamp, metric_type = 'SPEED') speed_latest_state,
    argMaxStateIf(value, timestamp, metric_type = 'STATUS') status_latest_state
FROM ComponentMetrics GROUP BY bucket_start, simulation_id, component_id, component_type;

CREATE TABLE IF NOT EXISTS analytics_location_transit_1m
(
    bucket_start DateTime('UTC'), simulation_id LowCardinality(String),
    from_location_id LowCardinality(String), to_location_id LowCardinality(String),
    traversal_count_state AggregateFunction(count), unique_items_state AggregateFunction(uniq, String),
    avg_transit_ms_state AggregateFunction(avg, Float64), max_transit_ms_state AggregateFunction(max, UInt64),
    p50_transit_ms_state AggregateFunction(quantile(0.5), Float64),
    p95_transit_ms_state AggregateFunction(quantile(0.95), Float64)
) ENGINE = AggregatingMergeTree ORDER BY (simulation_id, from_location_id, to_location_id, bucket_start);

CREATE MATERIALIZED VIEW IF NOT EXISTS mv_analytics_location_transit_1m
TO analytics_location_transit_1m AS
SELECT toStartOfMinute(event_timestamp) bucket_start, simulation_id, from_location_id, to_location_id,
    countState() traversal_count_state, uniqState(item_id) unique_items_state,
    avgState(toFloat64(transit_time_ms)) avg_transit_ms_state, maxState(transit_time_ms) max_transit_ms_state,
    quantileState(0.5)(toFloat64(transit_time_ms)) p50_transit_ms_state,
    quantileState(0.95)(toFloat64(transit_time_ms)) p95_transit_ms_state
FROM analytics_location_transit_events GROUP BY bucket_start, simulation_id, from_location_id, to_location_id;

CREATE TABLE IF NOT EXISTS analytics_destination_journeys_1m
(
    bucket_start DateTime('UTC'), simulation_id LowCardinality(String), destination_id String,
    completed_count_state AggregateFunction(count), avg_duration_ms_state AggregateFunction(avg, Float64),
    p50_duration_ms_state AggregateFunction(quantile(0.5), Float64),
    p95_duration_ms_state AggregateFunction(quantile(0.95), Float64),
    max_duration_ms_state AggregateFunction(max, UInt64)
) ENGINE = AggregatingMergeTree ORDER BY (simulation_id, destination_id, bucket_start);

CREATE MATERIALIZED VIEW IF NOT EXISTS mv_analytics_destination_journeys_1m
TO analytics_destination_journeys_1m AS
SELECT toStartOfMinute(exit_timestamp) bucket_start, simulation_id, chute_id destination_id,
    countState() completed_count_state, avgState(toFloat64(traversal_time_ms)) avg_duration_ms_state,
    quantileState(0.5)(toFloat64(traversal_time_ms)) p50_duration_ms_state,
    quantileState(0.95)(toFloat64(traversal_time_ms)) p95_duration_ms_state,
    maxState(traversal_time_ms) max_duration_ms_state
FROM analytics_completed_journeys GROUP BY bucket_start, simulation_id, destination_id;
