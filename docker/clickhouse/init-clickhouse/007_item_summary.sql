CREATE TABLE IF NOT EXISTS default.item_journeys
(
    simulation_id LowCardinality(String),
    item_id String,
    first_seen SimpleAggregateFunction(min, DateTime64(3)),
    last_seen SimpleAggregateFunction(max, DateTime64(3)),
    path_segments AggregateFunction(groupArrayArray, Array(String))
)
ENGINE = AggregatingMergeTree()
ORDER BY (simulation_id, item_id);

ALTER TABLE default.item_journeys
    ADD COLUMN IF NOT EXISTS simulation_id LowCardinality(String) DEFAULT 'live' FIRST;
