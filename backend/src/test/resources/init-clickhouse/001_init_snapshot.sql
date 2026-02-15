SET allow_experimental_object_type = 1;
CREATE TABLE IF NOT EXISTS default.snapshots
(
    `snapshot_id` UUID,
    `timestamp` DateTime64(3),
    `graph_data` JSON
)
ENGINE = MergeTree
PARTITION BY toYYYYMM(timestamp)
ORDER BY timestamp;