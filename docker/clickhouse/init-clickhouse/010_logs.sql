CREATE TABLE IF NOT EXISTS default.logs
(
    `timestamp` DateTime64(3, 'UTC'),
    `date` Date MATERIALIZED toDate(timestamp),
    `level` LowCardinality(String),
    `application` LowCardinality(String),
    `environment` LowCardinality(String),
    `logger` String,
    `thread` String,
    `message` String,
    `message_template` String,
    `simulation_id` String,
    `sender_id` String,
    `event_type` String,
    `event_id` String,
    `entity_id` String,
    `values` String,
    `stack_trace` String
)
ENGINE = MergeTree
PARTITION BY toYYYYMM(timestamp)
ORDER BY (simulation_id, logger, timestamp)
TTL toDateTime(timestamp) + INTERVAL 30 DAY;
