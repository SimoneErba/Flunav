CREATE TABLE IF NOT EXISTS default.analytics_components
(
    `location_id` LowCardinality(String),
    `total_items_passed` UInt64,
    `last_activity` SimpleAggregateFunction(max, DateTime64(3))
)
ENGINE = SummingMergeTree()
ORDER BY (location_id);