CREATE TABLE IF NOT EXISTS default.snapshots
(
    `snapshot_id` UUID,
    `timestamp` DateTime64(3, 'UTC'),
    `graph_data` JSON(
        max_dynamic_paths = 0,
        timestamp String,
        locations Array(Tuple(
            id String,
            name Nullable(String),
            type Nullable(String),
            active Nullable(Bool),
            latitude Nullable(Float64),
            longitude Nullable(Float64),
            capacity Nullable(Int32),
            properties JSON,
            customColor Nullable(String)
        )),
        conveyors Array(Tuple(
            id String,
            sourceId String,
            targetId String,
            name Nullable(String),
            length Nullable(Float64),
            speed Nullable(Float64),
            minDistance Nullable(Float64),
            type Nullable(String),
            active Nullable(Bool),
            mainPath Nullable(Bool),
            capacity Nullable(Int32),
            properties JSON,
            customColor Nullable(String)
        )),
        items Array(Tuple(
            id String,
            name Nullable(String),
            active Nullable(Bool),
            properties JSON,
            locationId Nullable(String),
            currentEdgeId Nullable(String),
            entryTimestamp Nullable(String),
            progress Nullable(Float64),
            destinations Array(String),
            selectedExitId Nullable(String),
            path Variant(Array(String), Nothing),
            customColor Nullable(String)
        ))
    )
)
ENGINE = MergeTree
PARTITION BY toYYYYMM(timestamp)
ORDER BY (timestamp, snapshot_id);

ALTER TABLE default.snapshots
    MODIFY COLUMN `timestamp` DateTime64(3, 'UTC'),
    MODIFY COLUMN `graph_data` JSON(
        max_dynamic_paths = 0,
        timestamp String,
        locations Array(Tuple(
            id String,
            name Nullable(String),
            type Nullable(String),
            active Nullable(Bool),
            latitude Nullable(Float64),
            longitude Nullable(Float64),
            capacity Nullable(Int32),
            properties JSON,
            customColor Nullable(String)
        )),
        conveyors Array(Tuple(
            id String,
            sourceId String,
            targetId String,
            name Nullable(String),
            length Nullable(Float64),
            speed Nullable(Float64),
            minDistance Nullable(Float64),
            type Nullable(String),
            active Nullable(Bool),
            mainPath Nullable(Bool),
            capacity Nullable(Int32),
            properties JSON,
            customColor Nullable(String)
        )),
        items Array(Tuple(
            id String,
            name Nullable(String),
            active Nullable(Bool),
            properties JSON,
            locationId Nullable(String),
            currentEdgeId Nullable(String),
            entryTimestamp Nullable(String),
            progress Nullable(Float64),
            destinations Array(String),
            selectedExitId Nullable(String),
            path Variant(Array(String), Nothing),
            customColor Nullable(String)
        ))
    );
