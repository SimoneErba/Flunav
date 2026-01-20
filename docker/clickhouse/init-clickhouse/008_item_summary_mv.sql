CREATE MATERIALIZED VIEW IF NOT EXISTS default.mv_item_journeys
TO default.item_journeys
AS
SELECT
    entity_id AS item_id,
    
    -- Per SimpleAggregateFunction, passiamo il valore calcolato
    min(timestamp_received) AS first_seen,
    max(timestamp_received) AS last_seen,
    
    -- FIX: 
    -- 1. Castiamo il JSON in Array(String)
    -- 2. Usiamo la funzione *State per popolare la colonna AggregateFunction
    groupArrayArrayState(CAST(data.path, 'Array(String)')) AS path_segments

FROM default.Events
WHERE event_type = 'PATH_TRAVERSED'
GROUP BY entity_id;