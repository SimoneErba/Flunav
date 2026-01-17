CREATE MATERIALIZED VIEW IF NOT EXISTS default.mv_analytics_components
TO default.analytics_components
AS SELECT
    -- "Esplode" l'array del segmento: genera una riga per ogni location nel path
    path_element AS location_id,
    count() AS total_items_passed,
    max(timestamp_received) AS last_activity
FROM default.Events
-- ARRAY JOIN è la magia: trasforma 1 evento con 10 step in 10 aggiornamenti
ARRAY JOIN JSONExtractArrayRaw(data, 'pathSegment') AS path_element
WHERE event_type = 'ITEM_PATH_SEGMENT';