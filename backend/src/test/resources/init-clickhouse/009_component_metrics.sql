CREATE TABLE IF NOT EXISTS ComponentMetrics
(
    `timestamp` DateTime64(3),
    `simulation_id` String,
    `component_id` String,
    `component_type` Enum('CONVEYOR' = 1, 'LOCATION' = 2),
    `metric_type` Enum('OCCUPANCY' = 1, 'SPEED' = 2, 'STATUS' = 3),
    `value` Float64
)
ENGINE = MergeTree
PARTITION BY toYYYYMM(timestamp)
ORDER BY (simulation_id, component_id, metric_type, timestamp)
TTL toDateTime(timestamp) + INTERVAL 30 DAY;
