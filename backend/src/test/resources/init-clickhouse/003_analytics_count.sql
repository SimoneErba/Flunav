CREATE TABLE IF NOT EXISTS default.analytics_time_series
(
    `bucket_start` DateTime64(3),
    `bucket_seconds` UInt16,
    `items_entered` UInt64,
    `items_exited` UInt64,
    `items_current` UInt64
)
ENGINE = MergeTree()
PARTITION BY toYYYYMM(bucket_start)
ORDER BY (bucket_start, bucket_seconds);
