CREATE MATERIALIZED VIEW IF NOT EXISTS default.mv_item_journeys
TO default.item_journeys
AS SELECT
    entity_id AS item_id,
    min(timestamp_received) AS first_seen,
    max(timestamp_received) AS last_seen,
    -- Aggiunge il segmento corrente alla collezione
    groupArrayArrayState(
        JSONExtractArrayRaw(data, 'pathSegment')
    ) AS path_segments
FROM default.Events
WHERE event_type = 'ITEM_PATH_SEGMENT'
GROUP BY item_id;