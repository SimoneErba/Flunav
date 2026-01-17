CREATE TABLE IF NOT EXISTS default.item_journeys
(
    `item_id` String,
    `first_seen` SimpleAggregateFunction(min, DateTime64(3)),
    `last_seen` SimpleAggregateFunction(max, DateTime64(3)),
    -- Colleziona tutti i segmenti in un array di array
    `path_segments` AggregateFunction(groupArrayArray, String)
)
ENGINE = AggregatingMergeTree()
ORDER BY (item_id);