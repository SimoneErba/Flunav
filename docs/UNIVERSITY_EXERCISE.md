# Reproducible conveyor experiments

Run the normal Flumen stack with Java 21, OrientDB, Redis, ClickHouse and RabbitMQ. Use an ADMIN or SUPERADMIN account to create projects and experiments; authenticated viewers can read them. The external Java simulator is unnecessary for teaching templates. Open **Scenarios** from the graph toolbar or **Setup → Scenario library**.

## Exercise

1. Instantiate **Simple line**. It opens a paused detached runtime containing two 3 m belts at 1 m/s. The source, junction and exit are stored within that runtime; edits do not change live topology.
2. Open the scenario library, give the model a name and save a revision. Duplicate it and open the duplicate. Select a conveyor, set speed to 0.5 m/s, submit, return to the library and save a new revision of the duplicate.
3. Select the first model as the comparison reference and the duplicate as the alternative. Use 300 seconds, five replications and seed 42. Create and run the comparison. Alternatively, select a conveyor and sweep speed using explicit values `0.5, 1, 2`.
4. Inspect completed and remaining items together. Unobstructed travel time is 6 seconds for the reference and 9 seconds after slowing one belt. Queues, spacing, failures and the fixed measurement horizon can change observed journey statistics.
5. Examine the mean paired difference and 95% Student-t interval. At least two completed matching replications are needed. Zero-variance differences have a zero-width interval. No completed journeys means journey statistics are unavailable. P5–P95 describes the distribution of runs, not uncertainty in a mean. Recirculation is passes per 100 completions and may exceed 100.
6. Download comparison JSON and the run CSV. Export each scenario as `.flusim`. On another installation, use **Import**, inspect the preview, then open an isolated copy. The files carry baseline topology, routing/display configuration, virtual start time, seed and experiment inputs. They never depend on the originating installation's live history.

## Supported semantics and limits

The scenario schema and model version are 1. Physical conveyor length, speed and spacing use meters and meters/second. Timestamps are UTC. Graph coordinates are visual coordinates. Portable initial items use `lengthMeters`. Export converts existing `lengthCm`/`length` values to meters, and restoration converts them back for the existing movement reducer. Generator version 2 creates 0.15 m items explicitly, independently of installation defaults. Item snapshot progress is a fraction from 0 to 1; sensor progress is a percentage from 0 to 100.

Generator version 2 uses separate SHA-256-derived streams for rate variation, arrival intervals, destination choice and each conveyor's failures. A stream seed is the first eight bytes, in big-endian order, of SHA-256 over the decimal run seed, newline and purpose. Run seeds equal base seed plus run index. Missing generator versions and version 1 preserve the legacy stream. Failure distributions with different parameters do not promise equal outage times.

Inputs support one source, fixed or Poisson arrivals, destination probabilities, rate variation, exponential failure intervals and fixed repair duration. Built-in parameters are illustrative teaching defaults, not manufacturer specifications. The merge layout uses a split/rejoin topology because independent arrival sources are not supported.

The default limits are 1,000 runs per experiment, 1,000 total runs per comparison, 10 MiB per scenario and three active interactive runtimes. Batch experiments use the existing bounded worker pool. Capacity and free-heap admission checks can reject new runtimes. Leave a runtime through Live to release it, or delete it through the existing simulation controls.

Files start new runs from immutable baselines. Worker handles, scheduled queues and live subscriptions are not checkpointed. Save revisions before restarting; reopen a saved revision into a fresh runtime. Interrupted experiments are marked failed after restart and completed results remain stored.

Reference cases for movement, spacing, staging, merge behavior, recirculation and stop/restart are exercised by the repository integration tests. Event timestamps govern virtual execution, and external events win timestamp ties. Numerical travel-time checks should allow scheduling/measurement precision (milliseconds); qualitative template results are not physical validation against measured conveyor data.

Before distributing a university release, review the repository and dependency licenses and record the tested application commit. This implementation does not publish a release automatically.
