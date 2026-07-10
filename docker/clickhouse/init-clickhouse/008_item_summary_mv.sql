CREATE MATERIALIZED VIEW IF NOT EXISTS default.mv_item_journeys
TO default.item_journeys
AS
SELECT
    simulation_id,
    item_id,
    min(event_timestamp) AS first_seen,
    max(event_timestamp) AS last_seen,
    groupArrayArrayState(path) AS path_segments
FROM default.analytics_path_traversal_ingest
GROUP BY simulation_id, item_id;
