# Backend Core Flows

This guide is an index for reading the event-driven core. It describes ownership
and call order; the Java code remains the source of truth for behavior.

## State boundaries

| State | OrientDB | Redis | ClickHouse | Internal scheduling |
| --- | --- | --- | --- | --- |
| Live | Durable topology and entities | Hot positions and occupancy | Append-only history | `LiveSystemScheduler` |
| Historical simulation | In-memory database | `sim:<id>:` namespace | Read-only replay source | `SimulationState.internalEventQueue` |
| Future/what-if simulation | In-memory database | `sim:<id>:` namespace | No future reads or writes as live history | `SimulationState.internalEventQueue` |

`DatabaseContextHolder` selects the active OrientDB database and Redis namespace.
Async work must re-enter both that context and `TimeService` virtual time.

## Event reduction

1. A controller, RabbitMQ listener, scheduler, replay worker, or simulation player
   submits a `DomainEvent` to `EventProcessor`.
2. `EventProcessor.process` chains entity events by live/simulation scope and entity
   id. Unrelated entities may execute concurrently.
3. `executeBusinessLogic` restores thread-local context and acquires a state
   barrier. Destructive topology events take the write lock; normal reductions use
   the read lock.
4. `processEvent` dispatches the event reducer. Reducers update OrientDB and Redis,
   then broadcast successful state changes when requested.
5. The outer live path appends replayable events to ClickHouse. Replay and
   simulation-generated events must not be appended as live history.

Start reading at:

- `EventProcessor.process` for ordering and asynchronous context propagation.
- `EventProcessor.executeBusinessLogic` for persistence and state barriers.
- `EventProcessor.processEvent` for event-type dispatch.
- `ItemMovementProcessor` for physics, accumulation, and next-event scheduling.

## Simulation lifecycle and replay

`SimulationService` owns lifecycle metadata, admission limits, playback state, and
the internal event queue. `HistoricalGraphBuilder` owns initial reconstruction.
`HistoricalEventPlayer` owns interactive clock advancement. `SimulationInputService`
owns the history-to-live handoff queue.

Historical construction follows this order:

1. Select the latest snapshot no later than the real-event replay boundary.
2. Restore destination configuration and graph/hot-state baseline.
3. Page ClickHouse events after the snapshot.
4. Merge external history with scheduled internal events by timestamp. External
   history wins ties so observations can invalidate projections before they fire.
5. Stop ClickHouse replay at physical now. If the target is later, continue using
   internal events only.
6. Checkpoint all moving items at the exact requested virtual timestamp.

Do not refresh an in-process `SimulationState` from Redis after it has been cached:
Redis does not contain its queue, locks, or playback generation.

## Destination mapping, priority, and routing

Routing has two mapping stages:

1. `DestinationMappingService` evaluates typed item-field rules at domain time and
   produces ordered logical destination ids.
2. `DestinationExitMappingService` expands each logical destination into ordered
   physical exit location ids. A destination that is already a location id can be
   used directly.

`RoutingDecisionService.selectRoute` then:

1. Resolves the current position to a source location. A conveyor source resolves
   to its target because that conveyor traversal is already committed.
2. Finds paths using active conveyors with positive speed.
3. Calculates exit demand from physical occupancy, existing assignments, and
   reservations for waiting urgent items.
4. Rejects candidates outside their priority-adjusted capacity limit.
5. Ranks candidates. Priority `0` favors lower utilization; priority `1` favors
   lower travel time; fractional values blend the normalized scores.
6. Falls back to a non-chute, preferably main-path conveyor when no exit can be
   assigned. Positive-priority items may wait for capacity; normal items continue
   recirculating.

`RoutingCoordinator` serializes the capacity-sensitive read/assign sequence within
one live or simulation namespace. Do not move only part of that sequence outside
the lock.

## Movement

`ItemMovementProcessor` uses one path for live and simulated item physics. It reads
the active context and sends projected events to either `LiveSystemScheduler` or
`SimulationService.addInternalEvent`.

The important invariant is accumulated distance: before speed changes, playback
rescheduling, or future internal events, moving items are checkpointed at the
relevant domain timestamp. Playback speed affects real waiting time only; it never
changes domain event timestamps or conveyor distance calculations.

Staging conveyors are handled separately because they retain ordered items and
release a planned transition as a group. Decision points may recalculate the route;
ordinary pass-through locations continue the existing assignment.

## Safe change checklist

- Use `timeService.now()` for event/domain time; use `physicalNow()` only for the
  real-world boundary.
- Establish simulation and virtual-time contexts again after every async boundary.
- Keep mapping tables, path caches, item state, and occupancy in the same Redis
  namespace.
- Invalidate only the active namespace's path caches after topology availability or
  cost changes.
- Broadcast only after reducer state changes succeed.
- Never append replayed or simulation-generated events as live ClickHouse history.
- Preserve external-before-internal ordering at equal timestamps.
