CREATE MATERIALIZED VIEW IF NOT EXISTS default.mv_analytics_components
TO default.analytics_components
AS
SELECT
    location_id,
    count() AS total_items_passed,
    max(timestamp_received) AS last_activity
FROM
(
    -- PATH_TRAVERSED events
    SELECT
        arrayJoin(CAST(data.path, 'Array(String)')) AS location_id,
        timestamp_received
    FROM default.Events
    WHERE event_type = 'PATH_TRAVERSED'
)
GROUP BY location_id;