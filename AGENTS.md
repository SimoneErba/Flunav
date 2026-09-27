# AGENTS.md - Flumen Agent Guidelines

This repository is a real-time digital twin for conveyor and sorting systems. Treat it as an event-driven simulation platform, not as a CRUD app. The important design constraint is that the system must be able to derive live state, historical state, and future simulation state from the same event model without letting those states leak into each other.

## Working Principles

- Read the local code before changing behavior. The backend has several context-sensitive services where a small call-site change can affect live mode, replay mode, and simulation mode differently.
- Keep changes scoped to the behavior being requested. Avoid opportunistic rewrites, broad formatting churn, dependency changes, or cleanup outside the touched path.
- Preserve event replayability. New behavior that changes state should normally be represented as an event in `commons/src/main/java/flunav/events/`, processed through `EventProcessor`, and persisted for replay when it belongs to domain history.
- When showing code to the user, provide complete files or complete relevant methods. Do not omit lines for brevity in generated code examples.
- Check `GEMINI.md` and `TODO.md` when a task touches architecture, simulation, analytics, anomaly detection, or roadmap behavior. Treat `TODO.md` as context, not authorization for adjacent work, and prefer current code and tests when documentation has drifted.

## Architecture

### Repository Modules

- `commons` contains shared event contracts used by `backend` and `simulator`. After changing it, install/build it first and verify every affected consumer.
- `backend` is the Java 21 Spring application; `simulator` is a Java 17 event producer; `opc-gateway` is the Java 21 OPC-to-RabbitMQ bridge.
- `frontend` is the browser application. `assistant` is an optional Node/Trigger.dev subsystem whose service tokens and provider secrets must remain server-side.
- Preserve the package manager and lockfile already used by the touched module; do not switch package managers or update dependencies unless the task requires it.

### Backend Shape

The backend follows Spring Boot layering, but the real architecture is event sourcing plus polyglot persistence:

- Controllers validate requests, create domain events, publish or process those events, and return HTTP responses.
- `EventProcessor` is the central reducer for domain events. It applies event effects to OrientDB and Redis, emits WebSocket updates, and records live events in ClickHouse.
- RabbitMQ decouples ingestion from processing for live external events.
- ClickHouse is the immutable event store and analytics store. It is the source for replay, snapshots, metrics, and historical queries.
- OrientDB stores graph topology and relatively durable graph entities: locations, conveyors, item metadata, display rules, and users.
- Redis stores hot operational state: active item positions, conveyor occupancy, location occupancy, simulation metadata, and namespaced simulation state.
- WebSocket broadcasts keep the frontend graph synchronized after event processing.

Do not bypass this flow casually. Direct database writes are acceptable only when the existing code is explicitly maintaining derived state, restoring snapshots, or cleaning isolated runtime state.

### Event Sourcing And Replay

Live events are append-only history. `ClickHouseService.saveEventAsync` stores live events, and replay code reads them back to reconstruct past state. Event classes in `commons` must stay immutable data carriers with Jackson-compatible constructors and stable field names.

When adding or changing events:

- Put shared event DTOs in `commons/src/main/java/flunav/events/`.
- Use `@JsonCreator` and `@JsonProperty` for deserialization.
- Keep event names and payload semantics stable; old ClickHouse rows must remain replayable.
- Prefer additive event evolution. If a breaking schema change is unavoidable, add explicit upcasting or compatibility handling before relying on the new shape.
- Do not put business logic in event classes.

`HistoricalGraphBuilder` restores simulations by loading the most recent ClickHouse snapshot before the restore point, replaying ClickHouse events after the snapshot, and then projecting internal events when the requested restore point is in the future. This means event processing must be deterministic with respect to timestamp, simulation context, and topology state.

### Simulation Context

Simulation isolation is based on `DatabaseContextHolder`, which uses `ThreadLocal` state for the active simulation id and transactional OrientDB session. `OrientDBService.getSession()` checks that context to decide whether to use the live database or an in-memory simulation database. Redis repositories apply the same idea by prefixing simulation keys with `sim:<simulationId>:` through `DatabaseContextHolder.getSimulationId()`.

Design rules for simulation-safe code:

- Any code that should operate on a simulation must run inside `DatabaseContextHolder.enterSimulationContext(simulationId)` and must close that context reliably with try-with-resources.
- Never cache a simulation id in a singleton service field. Read it from `DatabaseContextHolder` at the point of use.
- When work crosses async boundaries, explicitly re-enter the simulation context and virtual time context in the worker thread.
- Always clear or close thread-local context in `finally` or try-with-resources. Leaked simulation context can route live writes into simulation storage or simulation writes into live storage.
- Repository methods should use the context-aware helpers instead of manually composing live or simulation keys unless they already accept an explicit simulation id for cleanup or cross-context access.
- Simulation cleanup must remove both the in-memory OrientDB database/pool and all Redis keys for the simulation namespace.

### Internal Events And Virtual Time

The platform schedules domain events for item motion. In live mode, scheduled internal events go through `LiveSystemScheduler`; in simulation mode, they are held in `SimulationState` and processed by `SimulationService`.

Important design decisions:

- `TimeService` separates physical wall-clock time from virtual simulation time. Use `timeService.now()` for domain time and `physicalNow()` only when comparing with the real world.
- Future simulations must not read ClickHouse events beyond physical now. After that boundary, simulations advance by projected internal events.
- Internal and external events are merged by timestamp during replay. ClickHouse events win ties so real history can cancel or replace scheduled projections before they fire.
- Before processing a future internal event, simulation code checkpoints item physics to the event timestamp so accumulated conveyor distance remains consistent.
- Playback speed changes affect scheduling delay, not event timestamps.

### Event Ordering And Concurrency

`EventProcessor` uses virtual threads and a per-entity future chain to keep events for the same entity ordered while allowing unrelated entities to process concurrently. Do not replace this with broad synchronization unless there is a specific race that requires it.

When modifying processing code:

- Preserve ordering for `EntityEvent` instances with the same entity id.
- Be careful with calls that write both OrientDB and Redis. OrientDB holds durable topology/entity data; Redis holds derived hot state that replay and cleanup code may rebuild.
- Leave retry handling for OrientDB concurrent modification errors in the processing path unless the replacement handles the same conflict class.
- RabbitMQ deliveries are acknowledged only after `EventProcessor` completes. Preserve that boundary, route terminal failures to the DLQ, and make effects safe under redelivery where practical.
- Broadcast only after state changes are successfully applied.
- Do not persist simulation replay events to ClickHouse as live history.

### Persistence Responsibilities

- OrientDB: graph structure, entity metadata, users, display rules, and simulation graph clones.
- Redis: active item state, conveyor and location occupancy, scheduled/hot simulation metadata, transient caches, and namespaced simulation runtime state.
- ClickHouse: immutable domain events, graph snapshots, analytics aggregates, throughput and item history queries.
- RabbitMQ: live event transport and command/event decoupling.

Keep these boundaries intact. For example, item position in motion belongs in Redis for live speed, but replay needs enough event history and snapshot data to reconstruct it.

When changing ClickHouse schemas, update the runtime scripts under `docker/clickhouse/init-clickhouse/` and the corresponding Testcontainers schema under `backend/src/test/resources/init-clickhouse/`. Bootstrap scripts alone do not migrate existing volumes, so schema evolution must include an idempotent forward-upgrade path where existing deployments are affected.

When debugging live or test behavior, it is acceptable to inspect Redis and ClickHouse directly from the terminal to understand current runtime state, event history, snapshots, and analytics rows. Prefer read-only queries unless the task explicitly requires cleanup or state repair, and keep any manual cleanup scoped to the affected test or simulation data.

### Frontend Shape

The frontend is a React 18, Vite, TypeScript, Tailwind app centered on the graph visualization.

- Use functional components and hooks only.
- Keep graph interaction logic in hooks such as `useGraphInteractions` instead of spreading graph behavior through view components.
- Use generated API client types where available. Do not hand-edit `frontend/src/api-client/`; change the backend OpenAPI contract, regenerate the client, and update its call sites together.
- WebSocket handlers should parse defensively, filter local echoes by sender/client id, and inject envelope timestamps into payload handling where the existing pattern does so.
- Preserve dark mode and responsive behavior in Tailwind classes.
- Avoid `any` unless an existing generic integration boundary requires it. If a handler has to accept unknown payloads, narrow the type before use.

## Testing Policy

Tests in this project should exercise real integrations. Do not add Mockito, `@Mock`, `@MockBean`, fake repositories, fake services, in-memory substitutes, or hand-written stubs for infrastructure behavior. Use Testcontainers for Redis, ClickHouse, OrientDB, RabbitMQ, and any other external dependency the test path needs.

Existing tests may contain legacy mocks. Do not copy that pattern. If you modify a mocked test, prefer converting the touched path to the Testcontainers-backed integration style instead of adding more mocks.

Allowed test tools:

- Testcontainers-backed Spring integration tests.
- Real repositories and services wired by Spring.
- Real ClickHouse schemas initialized from `backend/src/test/resources/init-clickhouse/`.
- Real OrientDB initialized from test resources.
- Real Redis state with explicit cleanup between tests.
- HTTP test clients such as MockMvc only as request/response drivers; do not mock Spring beans behind them.

Verification guidance:

- Tests are slow. Run the narrowest relevant test class or method for the change, and prefer repeating the focused tests already used in the current chat when they cover the touched behavior.
- Do not run the full backend suite by default unless the change touches shared event processing, persistence configuration, replay semantics, or cross-module contracts.
- For frontend changes, run the relevant lint/build or focused browser check for the touched surface.
- If a useful verification step cannot be run because Docker, Testcontainers, network, or credentials are unavailable, report that clearly.
- Clean up test state deterministically: Redis keys, ClickHouse rows, OrientDB databases/pools, simulation ThreadLocals, and virtual time contexts.

## Java Guidelines

### Packages And Layers

Use the existing package structure:

```text
com.flunav.backend.{controllers|services|repositories|domain|entities|models|utils|exception|config|context|messaging}
```

Layering should remain Controller -> Service/EventProcessor -> Repository -> Storage. Controllers should not contain database orchestration or replay logic.

### Spring And Lombok

- Use constructor injection, follow nearby Lombok and Spring annotation patterns, and keep exception handling centralized in `GlobalExceptionHandler` where practical.
- Name events with an `Event` suffix and tests with a `Tests` suffix. Put request/response DTOs under `models/input` and `models/response` unless an established feature package is a better fit.

### Type Safety And Error Handling

- Avoid raw types. Prefer `Map<String, Object>`, typed DTOs, and enums.
- Use `Optional` for absent return values when it improves call-site clarity.
- Validate controller inputs before creating events or invoking services.
- Use HTTP statuses deliberately: bad input, not found, conflict, accepted async work, and unexpected server failures should not collapse into generic 500s.
- Log with entity ids, simulation ids, timestamps, and event types when relevant.

### Imports And Formatting

Organize imports into standard Java/Jakarta, third-party, and internal project imports. Match the surrounding file's formatting and avoid broad reformatting.

### Comments

Comments should explain why non-obvious behavior is necessary, especially around replay ordering, simulation context, virtual time, persistence, and concurrency. Add a short method-level comment for behaviorally important methods, but not for trivial or self-explanatory code. Remove stale comments and never add prompt-related commentary such as "fixed per request."

## TypeScript Guidelines

- Components use PascalCase filenames and names.
- Utility files use lowercase or established local naming.
- Custom hooks start with `use`.
- Keep global state in Context only when multiple distant components need it.
- Use `useMemo` and `useCallback` for expensive graph computations or callbacks passed deeply.
- Keep API, WebSocket, and graph interaction types explicit.
- Use Tailwind utilities and existing layout patterns. Inline styles are acceptable for dynamic graph or canvas values.
- Use `react-hot-toast` for user-visible failures where the existing UI does so.

## API And Contracts

- Keep REST endpoints in `*Controller` classes.
- Return DTOs, not OrientDB records or Redis hashes.
- Preserve OpenAPI compatibility when changing request or response models.
- When changing generated client inputs or outputs, update frontend call sites together with backend DTOs.
- WebSocket payloads should stay timestamp-aware and sender-aware.

## Security And Destructive Operations

- Preserve the stateless JWT and role rules in `SecurityConfig`. Endpoint changes must verify both authorized and rejected access where the distinction matters.
- Keep assistant service tokens, provider keys, database credentials, and other secrets out of frontend bundles, API responses, logs, and committed files.
- Do not run `docker/purge-all.sh`, `docker compose down -v`, delete database volumes, or perform equivalent data-destructive operations unless the user explicitly requests and confirms that scope.

## Operational Item Cleanup

Use `./scripts/clear-items.sh` to clear live item state without removing locations, conveyors, display rules, simulations, RabbitMQ messages, or ClickHouse history.

- Stop the backend and simulator/event producers before cleanup so scheduled or queued events cannot recreate items during the operation.
- Inspect the target and item counts with `./scripts/clear-items.sh --dry-run`.
- Execute the cleanup with `./scripts/clear-items.sh --yes`.
- The tool auto-detects the development or standard OrientDB and Redis containers. Use `--orient-container`, `--redis-container`, or `--database` only when targeting a different local Compose environment.
- Avoid `--allow-running` unless the caller has separately stopped item scheduling and ingestion; it only bypasses the safety check and cannot cancel in-memory scheduled events.

## Benchmarks

Backend benchmark runners live under `backend/src/benchmark` and are separate from JUnit tests. They are not run by `mvn test`; use the Maven `benchmark` profile and `exec:java` as documented in `backend/src/benchmark/README.md`.

The multi-simulation benchmark creates topology and captures the multi-simulation baseline before timing starts. Treat the reported wall time as the actual multi-simulation execution time from `multiSimulationService.start(...)` until terminal status. Do not add assertions to these runners.

Use fixed presets for comparable results, and write outputs under `backend/target/benchmark-results/`. That directory is ignored by git. To compare scheduler pressure, rerun the same presets with `-Dflunav.benchmark.concurrent-runs=<n>`; when running several presets in one app lifetime, this value is an app startup property.
