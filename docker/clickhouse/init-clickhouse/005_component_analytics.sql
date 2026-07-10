CREATE TABLE IF NOT EXISTS default.analytics_path_traversal_ingest
(
    event_timestamp DateTime64(3),
    simulation_id LowCardinality(String),
    item_id String,
    previous_position_id String,
    previous_position_type LowCardinality(String),
    new_position_id String,
    new_position_type LowCardinality(String),
    path Array(String)
)
ENGINE = Null;

CREATE TABLE IF NOT EXISTS default.analytics_components
(
    `simulation_id` LowCardinality(String),
    `location_id` LowCardinality(String),
    `total_items_passed` UInt64,
    `last_activity` SimpleAggregateFunction(max, DateTime64(3))
)
ENGINE = SummingMergeTree()
ORDER BY (simulation_id, location_id);

ALTER TABLE default.analytics_components
    ADD COLUMN IF NOT EXISTS `simulation_id` LowCardinality(String) DEFAULT 'live' FIRST;
