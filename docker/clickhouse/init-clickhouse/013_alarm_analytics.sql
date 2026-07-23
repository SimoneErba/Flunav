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
)
ENGINE = ReplacingMergeTree(version)
ORDER BY (simulation_id, event_id);

CREATE TABLE IF NOT EXISTS analytics_alarm_affected_items
(
    event_id String,
    alarm_id String,
    conveyor_id String,
    item_id String,
    captured_at DateTime64(3, 'UTC'),
    simulation_id LowCardinality(String),
    version DateTime64(3, 'UTC') DEFAULT now64(3)
)
ENGINE = ReplacingMergeTree(version)
ORDER BY (simulation_id, alarm_id, item_id);
