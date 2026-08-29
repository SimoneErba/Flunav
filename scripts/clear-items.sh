#!/usr/bin/env bash

set -euo pipefail

usage() {
    cat <<'EOF'
Usage: ./scripts/clear-items.sh [options]

Clears live item state from OrientDB and Redis while preserving topology,
simulation namespaces, ClickHouse history, RabbitMQ messages, and display rules.

Options:
  --dry-run                    Show what would be removed without changing data.
  --yes                        Confirm the destructive cleanup.
  --allow-running              Allow cleanup while a backend process is running.
  --orient-container NAME      OrientDB container name (auto-detected by default).
  --redis-container NAME       Redis container name (auto-detected by default).
  --database NAME              OrientDB database name (default: main).
  -h, --help                   Show this help text.
EOF
}

fail() {
    echo "Error: $*" >&2
    exit 1
}

container_is_running() {
    [[ "$(docker inspect --format '{{.State.Running}}' "$1" 2>/dev/null || true)" == "true" ]]
}

detect_container() {
    local requested=$1
    shift

    if [[ -n "$requested" ]]; then
        container_is_running "$requested" || fail "container '$requested' is not running"
        printf '%s\n' "$requested"
        return
    fi

    local candidate
    for candidate in "$@"; do
        if container_is_running "$candidate"; then
            printf '%s\n' "$candidate"
            return
        fi
    done

    fail "could not find a running container among: $*"
}

orient_command() {
    local statement=$1
    docker exec "$orient_container" sh -c '
        statement=$1
        database=$2
        : "${ORIENTDB_ROOT_PASSWORD:?ORIENTDB_ROOT_PASSWORD is not set in the container}"
        curl -fsS \
            -u "root:${ORIENTDB_ROOT_PASSWORD}" \
            -H "Content-Type: text/plain" \
            --data-binary "$statement" \
            "http://127.0.0.1:2480/command/${database}/sql"
    ' sh "$statement" "$database"
}

orient_item_count() {
    orient_command "SELECT count(*) AS count FROM Item" | jq -er '.result[0].count // 0'
}

collect_redis_keys() {
    redis_keys=()
    declare -gA seen_redis_keys=()
    seen_redis_keys=()

    local key
    if [[ "$(docker exec "$redis_container" redis-cli --raw EXISTS active_items)" == "1" ]]; then
        redis_keys+=("active_items")
        seen_redis_keys["active_items"]=1
    fi

    local pattern
    for pattern in "${redis_patterns[@]}"; do
        while IFS= read -r key; do
            [[ -n "$key" ]] || continue
            if [[ -z "${seen_redis_keys[$key]+present}" ]]; then
                redis_keys+=("$key")
                seen_redis_keys["$key"]=1
            fi
        done < <(docker exec "$redis_container" redis-cli --raw --scan --pattern "$pattern")
    done
}

delete_redis_keys() {
    local offset
    local -a batch
    for ((offset = 0; offset < ${#redis_keys[@]}; offset += 200)); do
        batch=("${redis_keys[@]:offset:200}")
        docker exec "$redis_container" redis-cli UNLINK "${batch[@]}" >/dev/null
    done
}

dry_run=false
confirmed=false
allow_running=false
orient_container=""
redis_container=""
database="main"

while (($# > 0)); do
    case "$1" in
        --dry-run)
            dry_run=true
            ;;
        --yes)
            confirmed=true
            ;;
        --allow-running)
            allow_running=true
            ;;
        --orient-container)
            shift
            (($# > 0)) || fail "--orient-container requires a value"
            orient_container=$1
            ;;
        --redis-container)
            shift
            (($# > 0)) || fail "--redis-container requires a value"
            redis_container=$1
            ;;
        --database)
            shift
            (($# > 0)) || fail "--database requires a value"
            database=$1
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            fail "unknown option '$1'"
            ;;
    esac
    shift
done

[[ "$database" =~ ^[A-Za-z0-9_.-]+$ ]] || fail "invalid database name '$database'"
command -v docker >/dev/null 2>&1 || fail "docker is required"
command -v jq >/dev/null 2>&1 || fail "jq is required"

orient_container=$(detect_container "$orient_container" flunav_dev_orientdb flunav_orientdb)
redis_container=$(detect_container "$redis_container" flunav_dev_redis flunav_redis)

redis_patterns=(
    "item:*"
    "conv:*:items"
    "conv:*:tail"
    "loc:*:items"
    "chute:*:occupancy"
)

orient_count=$(orient_item_count)
collect_redis_keys

echo "OrientDB container: $orient_container"
echo "OrientDB database:  $database"
echo "Redis container:    $redis_container"
echo "OrientDB items:      $orient_count"
echo "Redis item keys:     ${#redis_keys[@]}"

if $dry_run; then
    echo "Dry run only; no data was changed."
    exit 0
fi

$confirmed || fail "cleanup is destructive; rerun with --yes after reviewing --dry-run"

if ! $allow_running; then
    if pgrep -f '[c]om\.flunav\.backend\.BackendApplication' >/dev/null 2>&1 \
            || container_is_running flunav_backend; then
        fail "a backend process is running; stop it first or explicitly pass --allow-running"
    fi
fi

if ((${#redis_keys[@]} > 0)); then
    delete_redis_keys
fi
orient_command "TRUNCATE CLASS Item UNSAFE" >/dev/null

collect_redis_keys
remaining_orient_items=$(orient_item_count)

if ((${#redis_keys[@]} != 0)) || ((remaining_orient_items != 0)); then
    fail "cleanup verification failed: $remaining_orient_items OrientDB items and ${#redis_keys[@]} Redis item keys remain"
fi

echo "Cleared live items from OrientDB and Redis. Topology and history were preserved."
