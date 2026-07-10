CREATE MATERIALIZED VIEW IF NOT EXISTS default.mv_analytics_components
TO default.analytics_components
AS
SELECT
    simulation_id,
    location_id,
    count() AS total_items_passed,
    max(event_timestamp) AS last_activity
FROM
(
    SELECT
        simulation_id,
        arrayJoin(path) AS location_id,
        event_timestamp
    FROM default.analytics_path_traversal_ingest
)
GROUP BY simulation_id, location_id;
