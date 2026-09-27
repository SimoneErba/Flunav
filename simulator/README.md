# Flumen Simulator

Install the shared event artifact before building or running the simulator:

```bash
mvn -f ../commons/pom.xml install
mvn package
```

Run a scenario through RabbitMQ:

```bash
SIMULATION_MODE=rabbit mvn exec:java -Dexec.mainClass=flunav.simulator.App -Dexec.args="line"
SIMULATION_MODE=rabbit mvn exec:java -Dexec.mainClass=flunav.simulator.App -Dexec.args="loop"
SIMULATION_MODE=rabbit mvn exec:java -Dexec.mainClass=flunav.simulator.App -Dexec.args="multi"
SIMULATION_MODE=rabbit mvn exec:java -Dexec.mainClass=flunav.simulator.App -Dexec.args="large"
SIMULATION_MODE=rabbit mvn exec:java -Dexec.mainClass=flunav.simulator.App -Dexec.args="stress"
SIMULATION_MODE=rabbit mvn exec:java -Dexec.mainClass=flunav.simulator.App -Dexec.args="future"
```

The deterministic routing scenarios are RabbitMQ-only:

```bash
SIMULATION_MODE=rabbit mvn exec:java -Dexec.mainClass=flunav.simulator.App -Dexec.args="sorting-hub"
SIMULATION_MODE=rabbit mvn exec:java -Dexec.mainClass=flunav.simulator.App -Dexec.args="airport-baggage"
SIMULATION_MODE=rabbit mvn exec:java -Dexec.mainClass=flunav.simulator.App -Dexec.args="priority-capacity"
```

### Conveyor spacing merge

From `simulator/`, run a bounded burst of four items per feeder:

```bash
SIMULATION_MODE=rabbit mvn compile exec:java -Dexec.mainClass=flunav.simulator.App -Dexec.args="conveyor-spacing"
SIMULATION_MODE=rabbit SIMULATION_ACTION=destroy mvn exec:java -Dexec.mainClass=flunav.simulator.App -Dexec.args="conveyor-spacing"
```

The `CS-` topology merges a BELT and a ROLLER feeder into a slower shared conveyor. Even-numbered items declare `lengthCm=20` (and a lower-priority `length=40`); odd-numbered items use the backend's 15 cm fallback. The run ends after 27 seconds. Destroy removes the eight items and its topology.

### One-minute priority demo

From `simulator/`, with the backend and RabbitMQ running:

```bash
SIMULATION_MODE=rabbit mvn compile exec:java \
  -Dexec.mainClass=flunav.simulator.App -Dexec.args="priority-demo"
```

The original `priority-capacity` scenario remains available. This separate scenario uses `PD-` identifiers.
After setup, it runs for 60 seconds:

- Blue and green items share exits 1 and 2; orange items use exit 3.
- Items arrive every second; every fourth item has high priority, across all three colors.
- Conveyors run at 0.25 m/s: each three-meter segment, including ingress, takes twelve seconds.
  Each conveyor has capacity 60; exits retain capacity 10 and junctions have no capacity.
  The simulator spawns items at `PD-ENTRY`; the backend handles all item movement.
- At 15 seconds, the conveyors to exits 1 and 3 stop. Blue/green traffic diverts to exit 2,
  while orange items recirculate until exit 3 is repaired.
- Each exit has capacity 10. The demo does not empty the chutes automatically, so the backend
  reserves remaining capacity for high-priority items and routes overflow around the
  forty-eight-second recirculation loop.
- At 25 seconds, the conveyors to exits 1 and 3 restart.
- At 60 seconds, injection ends and the producer closes its RabbitMQ connection. The demo
  conveyors remain active so the backend can keep moving the remaining items. Backend event
  delivery can add a small delay.

Priority uses the existing backend capacity policy, not a strict global sort of all items.
Items already on a failed conveyor remain there until it restarts.
Setup publishes flight color display rules and demo destination mappings, like the original
scenario; use a demo environment and run only one producer for this topology at a time.
A rerun removes this scenario's previous items before starting its timer.

### Clean demo startup

Use the repository's `scripts/clear-demo.sh` for a fresh local demo without deleting
database volumes or users. It clears **all graph/items, simulation clones and runtime
state, ClickHouse events/snapshots/analytics, and Flumen RabbitMQ queues** in the selected
local stack. Deletion is permanent. Users, login tokens, display rules, routing settings,
logs, and schemas remain. The scenario itself still publishes its own display/routing rules.

From the repository root, inspect the targets first:

```bash
bash scripts/clear-demo.sh --dry-run
```

Stop the backend and all event producers (including simulators and OPC gateways), leaving
OrientDB, Redis, ClickHouse, and RabbitMQ running. Then run:

```bash
bash scripts/clear-demo.sh --yes
```

Once cleanup finishes, restart the backend in your IDE or with your usual command,
then launch `priority-demo` separately using the command above. The cleanup script
does not wait for or start any service or simulator.
If both Compose stacks are running, select `--stack dev` or `--stack standard`.
The history database is detected from its `Events` and `snapshots` tables, since older
bootstrap scripts may put them in `default` despite a different container database setting.
If multiple history databases exist, choose one with `--clickhouse-database NAME`.
The script requires Docker, `jq`, `curl`, and `pgrep`.
It refuses cleanup when it detects a running backend/producer or queue consumer.
External producers must be stopped manually. Do not restart them until cleanup finishes.
If cleanup fails, leave writers stopped, resolve the reported error, and rerun the script.

To remove only the demo topology and items, preserving history:

```bash
SIMULATION_MODE=rabbit SIMULATION_ACTION=destroy mvn exec:java \
  -Dexec.mainClass=flunav.simulator.App -Dexec.args="priority-demo"
```

Aliases:

- `hub` selects `sorting-hub`.
- `airport` selects `airport-baggage`.
- `long` and `future-long` select `future`.
- `performance` and `perf` select `stress`.
- `priority` and `reserved-capacity` select `priority-capacity`.

Destroy a scenario by setting `SIMULATION_ACTION=destroy`:

```bash
SIMULATION_MODE=rabbit SIMULATION_ACTION=destroy mvn exec:java \
  -Dexec.mainClass=flunav.simulator.App -Dexec.args="sorting-hub"

SIMULATION_MODE=rabbit SIMULATION_ACTION=destroy mvn exec:java \
  -Dexec.mainClass=flunav.simulator.App -Dexec.args="airport-baggage"

SIMULATION_MODE=rabbit SIMULATION_ACTION=destroy mvn exec:java \
  -Dexec.mainClass=flunav.simulator.App -Dexec.args="priority-capacity"
```

Destruction removes conveyors in reverse declaration order, then locations in reverse declaration order, and
publishes item deletion events for the scenario prefix. Set `SIMULATOR_CLEANUP_ITEMS` to control the cleanup range.

bash scripts/clear-demo.sh --yes --run
