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
