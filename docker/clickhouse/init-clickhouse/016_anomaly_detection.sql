CREATE TABLE IF NOT EXISTS analytics_anomaly_findings
(
    finding_id String,
    scope_id LowCardinality(String),
    simulation_id LowCardinality(String),
    detector LowCardinality(String),
    detector_version LowCardinality(String),
    temporal_mode LowCardinality(String),
    component_id String,
    component_type LowCardinality(String),
    item_id String,
    previous_position_id String,
    reported_position_id String,
    expected_intermediate_positions Array(String),
    observed_metrics_json String,
    baseline_mean Nullable(Float64),
    baseline_median Nullable(Float64),
    baseline_stddev Nullable(Float64),
    baseline_mad Nullable(Float64),
    z_score Nullable(Float64),
    modified_z_score Nullable(Float64),
    sample_count UInt64,
    baseline_window_start Nullable(DateTime64(3, 'UTC')),
    baseline_window_end Nullable(DateTime64(3, 'UTC')),
    severity LowCardinality(String),
    alarm_id String,
    alarm_state LowCardinality(String),
    observation_timestamp DateTime64(3, 'UTC'),
    tick_timestamp DateTime64(3, 'UTC'),
    version DateTime64(3, 'UTC') DEFAULT now64(3)
)
ENGINE = ReplacingMergeTree(version)
ORDER BY (scope_id, finding_id);

CREATE TABLE IF NOT EXISTS analytics_anomaly_incidents
(
    incident_id String,
    scope_id LowCardinality(String),
    simulation_id LowCardinality(String),
    probable_root_component_id String,
    confidence LowCardinality(String),
    finding_ids Array(String),
    component_ids Array(String),
    first_finding_timestamp DateTime64(3, 'UTC'),
    updated_at DateTime64(3, 'UTC'),
    version DateTime64(3, 'UTC') DEFAULT now64(3)
)
ENGINE = ReplacingMergeTree(version)
ORDER BY (scope_id, incident_id);

CREATE TABLE IF NOT EXISTS analytics_detector_baselines
(
    baseline_id String,
    scope_id LowCardinality(String),
    detector_version LowCardinality(String),
    detector LowCardinality(String),
    component_id String,
    path_key String,
    window_start DateTime64(3, 'UTC'),
    window_end DateTime64(3, 'UTC'),
    sample_count UInt64,
    mean Float64,
    population_stddev Float64,
    median Float64,
    mad Float64,
    minimum Float64,
    maximum Float64,
    calculated_at DateTime64(3, 'UTC'),
    epoch_started_at DateTime64(3, 'UTC'),
    version DateTime64(3, 'UTC') DEFAULT now64(3)
)
ENGINE = ReplacingMergeTree(version)
ORDER BY (scope_id, detector, path_key, baseline_id);

CREATE TABLE IF NOT EXISTS analytics_component_flow_events
(
    source_event_id String,
    event_timestamp DateTime64(3, 'UTC'),
    scope_id LowCardinality(String),
    simulation_id LowCardinality(String),
    component_id String,
    component_type LowCardinality(String),
    direction LowCardinality(String),
    item_id String,
    version DateTime64(3, 'UTC') DEFAULT now64(3)
)
ENGINE = ReplacingMergeTree(version)
PARTITION BY toYYYYMM(event_timestamp)
ORDER BY (scope_id, source_event_id, component_id, direction);

CREATE TABLE IF NOT EXISTS analytics_component_flow_1m
(
    bucket_start DateTime64(3, 'UTC'),
    scope_id LowCardinality(String),
    simulation_id LowCardinality(String),
    component_id String,
    component_type LowCardinality(String),
    arrivals UInt64,
    departures UInt64,
    throughput UInt64,
    pressure Int64,
    version DateTime64(3, 'UTC') DEFAULT now64(3)
)
ENGINE = ReplacingMergeTree(version)
PARTITION BY toYYYYMM(bucket_start)
ORDER BY (scope_id, component_type, component_id, bucket_start);

ALTER TABLE analytics_location_transit_events
    ADD COLUMN IF NOT EXISTS source_event_id String DEFAULT '' AFTER event_timestamp;

ALTER TABLE analytics_alarm_events
    ADD COLUMN IF NOT EXISTS finding_id String DEFAULT '' AFTER alarm_id;

ALTER TABLE analytics_alarm_events
    ADD COLUMN IF NOT EXISTS component_type LowCardinality(String) DEFAULT 'CONVEYOR' AFTER conveyor_id;

ALTER TABLE analytics_alarm_events
    ADD COLUMN IF NOT EXISTS source LowCardinality(String) DEFAULT 'MANUAL' AFTER typology;
