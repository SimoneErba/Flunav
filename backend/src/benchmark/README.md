# Backend Benchmarks

This folder contains integration-style performance runners. They are separate from
JUnit tests and are not executed by `mvn test`.

Run the multi-simulation benchmark from `backend` with:

```bash
./mvnw -Pbenchmark test-compile exec:java \
  -Dexec.mainClass=com.flunav.backend.benchmark.MultiSimulationBenchmarkRunner \
  -Dexec.classpathScope=test
```

The runner starts the same Redis, ClickHouse, OrientDB, and RabbitMQ
Testcontainers used by integration tests. It creates the benchmark topology and
captures the multi-simulation baseline before timing starts. The measured wall
time is only `multiSimulationService.start(...)` until the multi-simulation
reaches a terminal status. Results are written as JSON and CSV under
`target/benchmark-results/`.

`Items` means the expected total items generated in each simulated run. The
runner derives the arrival rate by dividing that number by the virtual duration.
The `long-hours` preset therefore uses four times the medium preset's item
counts so its 8-hour runs retain the same arrival rates and exercise four times
as much item work. Results include both `durationSeconds` and
`arrivalRatePerHour`, plus peak JVM heap and process RSS sampled every 250 ms
while a multi-simulation is running.

## Fixed Presets

Use presets first so results are comparable between runs:

```bash
# Run every fixed preset in one Spring/Testcontainers app lifetime.
./mvnw -Pbenchmark test-compile exec:java \
  -Dexec.mainClass=com.flunav.backend.benchmark.MultiSimulationBenchmarkRunner \
  -Dexec.classpathScope=test \
  -Dflunav.benchmark.presets=all

# Quick smoke run for checking that the benchmark harness still works.
./mvnw -Pbenchmark test-compile exec:java \
  -Dexec.mainClass=com.flunav.backend.benchmark.MultiSimulationBenchmarkRunner \
  -Dexec.classpathScope=test \
  -Dflunav.benchmark.preset=smoke

# Small local run with light item counts.
./mvnw -Pbenchmark test-compile exec:java \
  -Dexec.mainClass=com.flunav.backend.benchmark.MultiSimulationBenchmarkRunner \
  -Dexec.classpathScope=test \
  -Dflunav.benchmark.preset=small

# Medium run with recirculation and conveyor failures enabled.
./mvnw -Pbenchmark test-compile exec:java \
  -Dexec.mainClass=com.flunav.backend.benchmark.MultiSimulationBenchmarkRunner \
  -Dexec.classpathScope=test \
  -Dflunav.benchmark.preset=medium

# Same graph shape with many more runs.
./mvnw -Pbenchmark test-compile exec:java \
  -Dexec.mainClass=com.flunav.backend.benchmark.MultiSimulationBenchmarkRunner \
  -Dexec.classpathScope=test \
  -Dflunav.benchmark.preset=many-runs

# Longer virtual duration per run.
./mvnw -Pbenchmark test-compile exec:java \
  -Dexec.mainClass=com.flunav.backend.benchmark.MultiSimulationBenchmarkRunner \
  -Dexec.classpathScope=test \
  -Dflunav.benchmark.preset=long-hours

# Larger graph with more ingress lanes, exits, items, runs, and failures.
./mvnw -Pbenchmark test-compile exec:java \
  -Dexec.mainClass=com.flunav.backend.benchmark.MultiSimulationBenchmarkRunner \
  -Dexec.classpathScope=test \
  -Dflunav.benchmark.preset=big-graph
```


To compare scheduler pressure with the same preset sizes, force a lower
concurrency value and write to a separate output file:

```bash
./mvnw -Pbenchmark test-compile exec:java \
  -Dexec.mainClass=com.flunav.backend.benchmark.MultiSimulationBenchmarkRunner \
  -Dexec.classpathScope=test \
  -Dflunav.benchmark.presets=all \
  -Dflunav.benchmark.concurrent-runs=2 \
  -Dflunav.benchmark.output=target/benchmark-results/presets-all-low-concurrency.json
```

When multiple presets run in one app lifetime, the Spring property
`multi-simulation.max-concurrent-runs` is fixed when the app starts. If no
explicit `flunav.benchmark.concurrent-runs` is provided, the runner uses the
highest concurrent run count from the selected presets.

Preset details:

| Preset | Graphs | Expected items per run | Runs | Virtual duration | Arrival rate per hour | Concurrent runs | Failures |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `smoke` | `tiny:1:1:false` | `1` | `1` | 1 minute | 60 | 2 | 0 |
| `small` | `small:1:2:false` | `100,500` | `1,10` | 1 hour | 100,500 | 4 | 0 |
| `medium` | `medium:3:5:true` | `1000,2500` | `10,50` | 2 hours | 500,1,250 | 8 | 2 |
| `many-runs` | `medium:3:5:true` | `1000` | `100,500` | 2 hours | 500 | 16 | 2 |
| `long-hours` | `medium:3:5:true` | `4000,10000` | `10,50` | 8 hours | 500,1,250 | 8 | 2 |
| `big-graph` | `large:8:12:true` | `2500,5000` | `10,50` | 2 hours | 1,250,2,500 | 16 | 4 |

Useful properties:

```bash
-Dflunav.benchmark.preset=medium
-Dflunav.benchmark.presets=small,medium,big-graph
-Dflunav.benchmark.graphs=small:1:2:false,medium:3:5:true
-Dflunav.benchmark.item-counts=100,1000
-Dflunav.benchmark.run-counts=1,10,100
-Dflunav.benchmark.duration-seconds=3600
-Dflunav.benchmark.concurrent-runs=8
-Dflunav.benchmark.output=target/benchmark-results/my-run.json
```

Graph format is `name:ingressCount:exitCount:recirculation`.
