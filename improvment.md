# Flunav Improvement Plan

## Goal

The immediate target is a trustworthy, read-only pilot on one real facility. Flunav should first prove that it can ingest physical observations without loss, reconstruct incidents accurately, and distinguish observed state from estimated or simulated state.

Control-plane functionality, large-scale simulations, and additional AI features should remain secondary until event durability and replay correctness are proven.

## Phase 0 - Define the First Product Boundary

Position the initial Flunav product as:

- Live flow visualization.
- Item history and incident reconstruction.
- Missing-scan, invalid-sequence, and congestion alerts.
- Historical playback.
- Read-only integration with PLC and WMS systems.

Equipment commands should be disabled by default in pilot environments.

Define measurable requirements for:

- Zero loss of acknowledged events.
- Duplicate delivery producing no duplicate effect.
- Rebuilt state matching live state.
- Maximum ingestion and visualization latency.
- Recovery time.
- Expected events and active items per second.
- Alert precision and acceptable false-positive rate.

### Exit Gate

One documented pilot use case, representative dataset, topology, signal list, and agreed acceptance criteria.

## Phase 1 - Fix the Event Contract

Create a versioned event envelope shared by every producer and consumer:

```text
eventId
schemaVersion
eventType
entityId
facilityId
occurredAt
receivedAt
source
sourceSequence
correlationId
causationId
provenance
payload
```

`provenance` should distinguish:

- External observations.
- User commands.
- Domain-generated events.
- Scheduled predictions.
- Replay events.
- Simulation-generated events.

Required work:

- Preserve `eventId` during JSON deserialization instead of generating a new UUID.
- Add schema versions and explicit upcasters.
- Define stable ordering when timestamps are equal.
- Keep observation time separate from ingestion and processing time.
- Add contract tests across `commons`, `backend`, `simulator`, and `opc-gateway`.

Event migrations must be additive so old ClickHouse rows remain replayable.

### Exit Gate

An event survives serialization, deserialization, persistence, and replay with identical identity and semantics, including events loaded from old fixtures.

## Phase 2 - Establish Durable Ingestion and Idempotency

Replace the current reduce-then-enqueue flow with:

```text
Producer -> durable acceptance -> projection -> analytics -> acknowledgement
```

Make an explicit architecture decision for the authoritative event journal. Benchmark a transactional append-only store, a Kafka-compatible log, or RabbitMQ Streams instead of assuming that ClickHouse should provide transactional ingestion semantics. ClickHouse should continue serving analytics unless testing demonstrates that it can also satisfy the durability contract.

Implement:

- Producer confirms.
- A durable inbox keyed by `eventId`.
- Idempotent event acceptance.
- Projection status and checkpoints.
- A distinction between retryable and terminal failures.
- Operator-visible DLQ inspection and replay.
- Backpressure during storage outages.
- RabbitMQ acknowledgement only after durable acceptance.
- Idempotent reducers for redelivery.

Avoid adding another datastore automatically. Select the journal based on measured throughput, operational simplicity, ordering guarantees, and recovery behavior.

### Exit Gate

Killing the backend at every ingestion step loses no acknowledged event, and delivering the same event repeatedly produces one historical record and one state transition.

## Phase 3 - Make Projections and Recovery Deterministic

Treat OrientDB and Redis as disposable projections of the authoritative journal.

Implement:

- A global journal sequence or facility-partition sequence instead of ordering exclusively by timestamps.
- Projection checkpoints.
- Rebuild into a separate namespace followed by verification and atomic activation.
- Periodic live-versus-rebuilt state hashes.
- Reconciliation reports for Redis, OrientDB, and event history.
- Snapshot metadata containing journal position, topology version, and schema version.
- Startup failure when recovery is incomplete instead of continuing as healthy.
- Dependency-aware readiness checks.

Define consistency boundaries for operations involving multiple entities:

- Item movement.
- Chute capacity.
- Routing decisions.
- Topology mutation.
- Alarm state.

For the initial deployment, a single active reducer per facility is acceptable if it is documented and enforced. Multi-instance processing should follow only after partition ownership and cross-entity ordering are defined.

### Exit Gate

Rebuilding the same journal multiple times produces equivalent logical state and never mutates the live namespace during verification.

## Phase 4 - Model Imperfect Physical Observations

Introduce uncertainty instead of representing every item as having an exact known position.

Track:

- Last observed position and timestamp.
- Estimated position.
- Confidence or state classification: `OBSERVED`, `ESTIMATED`, `STALE`, `LOST`, or `CONFLICTED`.
- Expected sensor window.
- Source clock and quality information.
- Conflicting or duplicate observations.

Harden the OPC gateway with:

- OPC UA security modes, certificates, and credential handling.
- Source timestamps and quality codes.
- Durable local buffering during RabbitMQ outages.
- Publisher confirms.
- Reconnection and resubscription verification.
- Sequence-gap detection.
- Integration tests against a real containerized or recorded OPC endpoint.

Build test datasets containing missing, late, duplicate, conflicting, and out-of-order events.

### Exit Gate

Flunav degrades explicitly from observed to estimated state and raises a useful data-quality alert instead of silently displaying false certainty.

## Phase 5 - Formalize Counterfactual Simulation

Implement the existing `WHAT_IF_PLAN.md` only after durable ingestion and deterministic replay ordering are complete.

The simulation policy must separate:

- Exogenous inputs that remain valid after a fork, such as arrivals or demand.
- Endogenous outcomes caused by the original system, such as positions, routes, and exits.
- Manual scenario changes.
- Internally generated movement.

Required behavior:

- Atomically establish a fork journal position.
- Ignore incompatible real-world outcomes after the fork.
- Give every simulation event explicit provenance.
- Store scenario mutations as isolated events.
- Use deterministic random seeds where decisions involve randomness.
- Report scenario assumptions and confidence alongside KPI differences.
- Prevent simulation writes from entering live Redis, OrientDB, WebSocket topics, command queues, or event history.

Keep the current limit on full-clone simulations initially. Copy-on-write should remain a measured, post-pilot optimization.

### Exit Gate

A known historical scenario can be reproduced, modified, repeated deterministically, and explained without live-state leakage.

## Phase 6 - Validate Analytics With Operators

Do not describe anomaly detection as predictive until its results are validated against real incidents.

For every detector:

- Define the operational question it answers.
- Record why an alert fired and its contributing observations.
- Track acknowledgements, false positives, and missed incidents.
- Measure time to detection and time saved during investigation.
- Version baselines and thresholds.
- Detect baseline drift.
- Separate data-quality alerts from equipment-health alerts.
- Backtest against labeled historical incidents.

Prioritize simple, actionable detectors:

1. Missing expected scan.
2. Invalid graph transition.
3. Excessive dwell time.
4. Growing queue pressure.
5. Increased recirculation.

### Exit Gate

Operators confirm that alerts reduce investigation time and meet an agreed precision target.

## Phase 7 - Production Engineering

Before calling Flunav production-ready:

- Remove committed and default production secrets.
- Require externally supplied secrets and document rotation.
- Authenticate and authorize WebSocket subscriptions.
- Apply roles consistently to mutation endpoints.
- Add request-rate and payload-size limits.
- Add dependency-aware health, readiness, and liveness checks.
- Monitor ingestion lag, journal lag, projection failures, queue depth, replay duration, simulation memory, and state divergence.
- Create backup and restore procedures for every durable store.
- Perform scheduled restore drills.
- Separate optional assistant infrastructure into its own deployment profile.
- Document upgrades and idempotent forward database migrations.

CI must:

- Compile every module in dependency order.
- Run unit and event-contract tests.
- Run focused Testcontainers integration tests.
- Run frontend lint, build, type checks, and browser tests.
- Test the OPC gateway and simulator.
- Build release images only after all required checks pass.
- Reject releases containing insecure defaults or unverified migrations.
- Stop publishing release images with tests skipped.

### Exit Gate

A release cannot be produced with failing tests, missing dependencies, insecure defaults, or an unverified migration. Backup restoration and crash recovery have both been demonstrated.

## Phase 8 - Scale From Measured Evidence

After the pilot, measure:

- Events per second.
- Number of active items.
- Topology size.
- Replay duration.
- Memory per simulation.
- Query latency.
- Number of concurrent users and simulations.

Use those measurements to decide whether to:

- Implement copy-on-write simulations.
- Partition processing by facility or routing resource.
- Add distributed WebSocket broadcasting.
- Scale ClickHouse horizontally.
- Retain or replace OrientDB.
- Consolidate persistence technologies.

Do not implement these changes solely because they appear on an enterprise checklist.

## Delivery Order

The strict priority is:

1. Product boundary and operational safety.
2. Stable event identity and versioning.
3. Durable acceptance and idempotency.
4. Deterministic rebuild and reconciliation.
5. Imperfect-sensor handling.
6. What-if correctness.
7. Analytics validation.
8. Production operations and measured scaling.

The central completion criterion is:

> Every acknowledged physical observation can be recovered exactly once, replayed deterministically, and clearly distinguished from an estimate or simulation.
