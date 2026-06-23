#!/bin/bash
set -euo pipefail

database="${CLICKHOUSE_DB:-default}"
if [[ ! "$database" =~ ^[A-Za-z0-9_]+$ ]]; then
    echo "Invalid ClickHouse database name: $database" >&2
    exit 1
fi

client=(clickhouse-client --user "${CLICKHOUSE_USER:-default}" --password "${CLICKHOUSE_PASSWORD:-}")
table_exists="$("${client[@]}" --query "EXISTS TABLE ${database}.analytics_time_series")"

if [[ "$table_exists" == "1" ]]; then
    schema_valid="$("${client[@]}" --query "
        SELECT count() = 5 AND countIf(
            (name = 'bucket_start' AND type = 'DateTime64(3)') OR
            (name = 'bucket_seconds' AND type = 'UInt16') OR
            (name = 'items_entered' AND type = 'UInt64') OR
            (name = 'items_exited' AND type = 'UInt64') OR
            (name = 'items_current' AND type = 'UInt64')
        ) = 5
        FROM system.columns
        WHERE database = '${database}' AND table = 'analytics_time_series'
    ")"

    if [[ "$schema_valid" != "1" ]]; then
        legacy_table="analytics_time_series_legacy_$(date -u +%Y%m%d%H%M%S)_$$"
        "${client[@]}" --query \
            "RENAME TABLE ${database}.analytics_time_series TO ${database}.${legacy_table}"
    fi
fi

"${client[@]}" --multiquery --query "
    CREATE TABLE IF NOT EXISTS ${database}.analytics_time_series
    (
        bucket_start DateTime64(3),
        bucket_seconds UInt16,
        items_entered UInt64,
        items_exited UInt64,
        items_current UInt64
    )
    ENGINE = MergeTree()
    PARTITION BY toYYYYMM(bucket_start)
    ORDER BY (bucket_start, bucket_seconds);
"

schema_valid="$("${client[@]}" --query "
    SELECT count() = 5 AND countIf(
        (name = 'bucket_start' AND type = 'DateTime64(3)') OR
        (name = 'bucket_seconds' AND type = 'UInt16') OR
        (name = 'items_entered' AND type = 'UInt64') OR
        (name = 'items_exited' AND type = 'UInt64') OR
        (name = 'items_current' AND type = 'UInt64')
    ) = 5
    FROM system.columns
    WHERE database = '${database}' AND table = 'analytics_time_series'
")"

if [[ "$schema_valid" != "1" ]]; then
    echo "analytics_time_series schema validation failed" >&2
    exit 1
fi
