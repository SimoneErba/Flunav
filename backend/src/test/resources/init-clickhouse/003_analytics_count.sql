CREATE TABLE IF NOT EXISTS default.analytics_time_series
(
    `minute` DateTime,
    `items_entered` UInt32,
    `items_exited` UInt32,
    `movements_count` UInt32
)
ENGINE = SummingMergeTree()
PARTITION BY toYYYYMM(minute)
ORDER BY (minute);