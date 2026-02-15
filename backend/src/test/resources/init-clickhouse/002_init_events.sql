CREATE TABLE IF NOT EXISTS default.Events
(
    `timestamp_received` DateTime64(3),
    `timestamp_processed` DateTime64(3),
    `event_type` LowCardinality(String),
    `entity_id` Nullable(String),
    `event_id` UUID,
    `data` JSON
)
ENGINE = MergeTree
PARTITION BY toYYYYMM(timestamp_received)
ORDER BY (timestamp_received, timestamp_processed, event_type, event_id);