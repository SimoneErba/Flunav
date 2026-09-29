CREATE TABLE IF NOT EXISTS simulation_comparisons
(
    comparison_id String,
    definition_json String,
    version UInt64
)
ENGINE = ReplacingMergeTree(version)
ORDER BY comparison_id;
