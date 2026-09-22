#!/usr/bin/env bash
set -euo pipefail

usage() {
    cat <<'EOF'
Usage: bash scripts/clear-demo.sh [--dry-run | --yes] [--stack dev|standard]

Clear the local demo's graph, items, simulation clones, runtime keys, event
history, snapshots, analytics, and queued events. Preserve users, login tokens,
display rules, routing settings, logs, schemas, and database volumes.

Stop the backend, simulator, and OPC/event producers first. This script never
kills processes or deletes volumes. External producers must also be stopped.

  --dry-run              Inspect targets/counts without changing anything (default).
  --yes                  Perform the irreversible data cleanup.
  --stack dev|standard   Select local Compose containers (auto-detected if unique).
  --clickhouse-database NAME
                         Select history database (otherwise detect Events/snapshots).
  -h, --help             Show this help.

Requires Docker, jq, curl, and pgrep. Cleanup never starts the backend or simulator.
EOF
}

fail() { echo "Error: $*" >&2; exit 1; }
running() { [[ "$(docker inspect --format '{{.State.Running}}' "$1" 2>/dev/null || true)" == true ]]; }

mode=dry
stack=""
clickhouse_database=""
while (($#)); do
    case "$1" in
        --dry-run) mode=dry ;;
        --yes) mode=clear ;;
        --stack) shift; (($#)) || fail '--stack requires a value'; stack=$1 ;;
        --clickhouse-database) shift; (($#)) || fail '--clickhouse-database requires a value'; clickhouse_database=$1 ;;
        -h|--help) usage; exit 0 ;;
        *) fail "unknown option: $1" ;;
    esac
    shift
done
for dependency in docker jq curl pgrep; do
    command -v "$dependency" >/dev/null || fail "$dependency is required"
done
docker info >/dev/null

if [[ -z "$stack" ]]; then
    if running flunav_dev_orientdb && running flunav_orientdb; then
        fail 'both stacks are running; choose --stack dev or --stack standard'
    elif running flunav_dev_orientdb; then
        stack=dev
    else
        stack=standard
    fi
fi
case "$stack" in
    dev) prefix=flunav_dev ;;
    standard) prefix=flunav ;;
    *) fail 'stack must be dev or standard' ;;
esac
orient_container=${prefix}_orientdb
redis_container=${prefix}_redis
clickhouse_container=${prefix}_clickhouse
rabbit_container=${prefix}_rabbitmq
for container in "$orient_container" "$redis_container" "$clickhouse_container" "$rabbit_container"; do
    running "$container" || fail "container $container is not running"
done

# Scheduling and ingestion must be quiescent before deleting derived state/history.
check_stopped() {
    if pgrep -f '[c]om\.flunav\.backend\.BackendApplication|[f]lunav\.simulator\.App|[f]lunav.*[Oo]pc|[b]ackend.*\.jar|[s]imulator.*\.jar|[o]pc-gateway.*\.jar' >/dev/null; then
        fail 'stop local backend, simulator, and OPC Java processes before cleanup'
    fi
    for container in flunav_backend flunav_dev_backend flunav_simulator flunav_dev_simulator flunav_opc_gateway flunav_opc-gateway; do
        if running "$container"; then
            fail "stop $container before cleanup"
        fi
    done
    if curl --silent --output /dev/null --max-time 2 http://localhost:8080/api/health; then
        fail 'a backend is still listening on localhost:8080; stop it before cleanup'
    fi
}
if [[ "$mode" == clear ]]; then check_stopped; fi

orient_request() {
    docker exec "$orient_container" sh -c '
        : "${ORIENTDB_ROOT_PASSWORD:?missing OrientDB root password}"
        curl -fsS --max-time 120 -u "root:$ORIENTDB_ROOT_PASSWORD" \
            -H "Content-Type: text/plain" -X "$1" --data-binary "$3" \
            "http://127.0.0.1:2480/$2"
    ' sh "$1" "$2" "${3:-}"
}
orient_sql() { orient_request POST command/main/sql "$1"; }
ch() {
    docker exec "$clickhouse_container" sh -c '
        : "${CLICKHOUSE_DB:?missing ClickHouse database}"
        : "${CLICKHOUSE_USER:?missing ClickHouse user}"
        : "${CLICKHOUSE_PASSWORD:?missing ClickHouse password}"
        exec clickhouse-client --user "$CLICKHOUSE_USER" --password "$CLICKHOUSE_PASSWORD" \
            --database "${2:-$CLICKHOUSE_DB}" --query "$1"
    ' sh "$1" "$clickhouse_database"
}
redis() { docker exec "$redis_container" redis-cli --raw "$@"; }

# Explicit ownership filters keep configuration keys and unrelated databases intact.
runtime_keys() {
    local pattern keys
    for pattern in 'active_items' 'item:*' 'conv:*' 'loc:*' 'pathcache:*' 'anomaly:*' 'sim:*'; do
        keys=$(redis --scan --pattern "$pattern") || return
        if [[ -n "$keys" ]]; then printf '%s\n' "$keys"; fi
    done
}
simulation_databases() {
    orient_request GET listDatabases | jq -r '
        .databases | (if type == "array" then .[]
                     elif type == "object" then keys[]
                     else error("invalid database listing") end)
        | select(test("^sim_[0-9a-f]{32}$"))'
}

if [[ -z "$clickhouse_database" ]]; then
    candidates=$(ch "SELECT database FROM system.tables
        WHERE name IN ('Events', 'snapshots') AND engine NOT IN ('View', 'MaterializedView')
        GROUP BY database HAVING uniqExact(name) = 2 ORDER BY database FORMAT TSV")
    [[ -n "$candidates" ]] || fail 'no ClickHouse database contains both Events and snapshots'
    [[ "$candidates" != *$'\n'* ]] || fail "multiple history databases found: $candidates; select --clickhouse-database NAME"
    clickhouse_database=$candidates
fi
[[ "$clickhouse_database" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] || fail 'invalid ClickHouse database name'

tables_sql="SELECT name FROM system.tables WHERE database = currentDatabase()
    AND engine NOT IN ('View', 'MaterializedView', 'Null')
    AND (name IN ('Events', 'snapshots', 'item_journeys', 'ComponentMetrics')
         OR startsWith(name, 'analytics_')) ORDER BY name FORMAT TSV"
tables=$(ch "$tables_sql")
[[ "$tables" == *Events* && "$tables" == *snapshots* ]] || fail "expected Events and snapshots tables not found in ClickHouse database $clickhouse_database"
simulations=$(simulation_databases)
keys=$(runtime_keys)
queues=$(docker exec "$rabbit_container" rabbitmqctl -q list_queues name messages consumers)

echo "Targets: $orient_container/main, $redis_container/0, $clickhouse_container/$clickhouse_database, $rabbit_container/vhost /"
for class in Item Conveyor Location; do
    count=$(orient_sql "SELECT count(*) AS count FROM $class" | jq -er '.result[0].count')
    echo "OrientDB $class records: $count"
done
echo "Simulation databases: ${simulations:-none}"
key_count=$(printf '%s' "$keys" | awk 'NF {n++} END {print n+0}')
echo "Redis runtime keys: $key_count"
while IFS= read -r table; do
    [[ "$table" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] || fail 'unexpected ClickHouse table name'
    echo "ClickHouse $table rows: $(ch "SELECT count() FROM $table")"
done <<< "$tables"
echo 'RabbitMQ queues to purge (name / messages / consumers):'
printf '%s\n' "$queues" | awk '$1 ~ /^(item-events-queue|item-events-dlq|commands|path-assignments)$/ {print}'
if [[ "$mode" == dry ]]; then
    echo 'Dry run only; no data changed. Stop writers, then use --yes.'
    exit 0
fi

check_stopped
queues=$(docker exec "$rabbit_container" rabbitmqctl -q list_queues name messages consumers)
if printf '%s\n' "$queues" | awk '$1 ~ /^(item-events-queue|item-events-dlq|commands|path-assignments)$/ && $3 != 0 {found=1} END {exit !found}'; then
    fail 'RabbitMQ still has consumers on demo queues; stop them first'
fi
while read -r queue _; do
    case "$queue" in
        item-events-queue|item-events-dlq|commands|path-assignments)
            docker exec "$rabbit_container" rabbitmqctl purge_queue "$queue" >/dev/null ;;
    esac
done <<< "$queues"

orient_sql 'DELETE EDGE Conveyor' >/dev/null
orient_sql 'DELETE VERTEX Item' >/dev/null
orient_sql 'DELETE VERTEX Location' >/dev/null
while IFS= read -r simulation; do
    [[ -n "$simulation" ]] || continue
    orient_request DELETE "database/$simulation" >/dev/null
done <<< "$simulations"
if [[ -n "$keys" ]]; then
    while IFS= read -r key; do redis UNLINK "$key" >/dev/null; done <<< "$keys"
fi
while IFS= read -r table; do
    ch "TRUNCATE TABLE $table SYNC"
done <<< "$tables"

for class in Item Conveyor Location; do
    [[ "$(orient_sql "SELECT count(*) AS count FROM $class" | jq -er '.result[0].count')" == 0 ]] || fail "$class cleanup incomplete"
done
[[ -z "$(runtime_keys)" ]] || fail 'runtime keys remain; check for active writers'
[[ -z "$(simulation_databases)" ]] || fail 'simulation databases remain'
while IFS= read -r table; do
    [[ "$(ch "SELECT count() FROM $table")" == 0 ]] || fail "$table is not empty; check for active writers"
done <<< "$tables"
remaining_queues=$(docker exec "$rabbit_container" rabbitmqctl -q list_queues name messages)
if printf '%s\n' "$remaining_queues" | awk '$1 ~ /^(item-events-queue|item-events-dlq|commands|path-assignments)$/ && $2 != 0 {found=1} END {exit !found}'; then
    fail 'queue messages remain; check for active producers'
fi
echo 'Demo data cleared permanently. Users, settings, logs, schemas, and volumes preserved.'
