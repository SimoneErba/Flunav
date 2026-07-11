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
)
ENGINE = ReplacingMergeTree(version)
ORDER BY (simulation_id, candidate_id);

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
)
ENGINE = ReplacingMergeTree(version)
ORDER BY (simulation_id, journey_id);

CREATE TABLE IF NOT EXISTS analytics_recirculation_facts
(
    recirculation_id String,
    event_timestamp DateTime64(3, 'UTC'),
    simulation_id LowCardinality(String),
    item_id String,
    previous_path Array(String),
    new_path Array(String),
    version DateTime64(3, 'UTC') DEFAULT now64(3)
)
ENGINE = ReplacingMergeTree(version)
ORDER BY (simulation_id, recirculation_id);

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
)
ENGINE = ReplacingMergeTree(version)
ORDER BY (simulation_id, event_id);
