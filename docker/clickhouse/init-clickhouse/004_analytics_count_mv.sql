CREATE MATERIALIZED VIEW IF NOT EXISTS default.mv_analytics_time_series 
TO default.analytics_time_series
AS SELECT
    toStartOfMinute(timestamp_received) AS minute,
    countIf(event_type = 'ITEM_CREATED') AS items_entered,
    countIf(event_type = 'ITEM_DELETED') AS items_exited,
    countIf(event_type = 'ITEM_POSITION_CHANGED') AS movements_count
FROM default.Events
GROUP BY minute;