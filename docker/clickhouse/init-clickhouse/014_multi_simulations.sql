CREATE TABLE IF NOT EXISTS multi_simulations
(
    experiment_id String,
    name String,
    status LowCardinality(String),
    configuration_json String,
    baseline_json String,
    topology_version String,
    configuration_version String,
    base_seed Int64,
    total_runs UInt32,
    completed_runs UInt32,
    failed_runs UInt32,
    created_at DateTime64(3, 'UTC'),
    started_at Nullable(DateTime64(3, 'UTC')),
    completed_at Nullable(DateTime64(3, 'UTC')),
    cancel_requested UInt8,
    error String,
    version UInt64
)
ENGINE = ReplacingMergeTree(version)
ORDER BY experiment_id;

CREATE TABLE IF NOT EXISTS multi_simulation_runs
(
    experiment_id String,
    run_index UInt32,
    seed Int64,
    status LowCardinality(String),
    effective_arrival_rate Float64,
    started_at Nullable(DateTime64(3, 'UTC')),
    completed_at Nullable(DateTime64(3, 'UTC')),
    metrics_json String,
    error String,
    version UInt64
)
ENGINE = ReplacingMergeTree(version)
ORDER BY (experiment_id, run_index);

CREATE TABLE IF NOT EXISTS multi_simulation_reports
(
    experiment_id String,
    report_json String,
    generated_at DateTime64(3, 'UTC'),
    version UInt64
)
ENGINE = ReplacingMergeTree(version)
ORDER BY experiment_id;
