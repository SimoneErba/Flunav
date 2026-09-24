# Event-Scoped DSE Checkpointing

## Goal

Remove the full active-item checkpoint performed before every internal simulation event. Every event must still advance the simulation clock and produce correct state at its timestamp, but unrelated moving items do not need their derived Redis positions rewritten.

## Current behavior

`SimulationService.processInternalEvent` calls `checkpointAllItems` before reducing each queued event. That loads every active item from Redis, resolves its conveyor, and writes a new checkpoint for every moving item. As the active population grows, a run approaches `events × active items` work.

The movement engine already has scoped checkpoint behavior for changes that affect multiple items:

- conveyor deactivation and stopping alarms checkpoint the conveyor occupants before stopping;
- speed changes checkpoint occupants with the old speed before rescheduling;
- length and conveyor-type changes checkpoint before changing physics;
- conveyor activation resumes occupants from their frozen checkpoints;
- staging conveyors checkpoint their coupled FIFO population.

Item activation and deactivation now also maintain an explicit Redis movement-paused flag. Deactivation checkpoints the affected item before cancelling its scheduled event. Activation clears the pause, resets the checkpoint timestamp, and schedules movement from the frozen progress. Graph projection, conveyor recalculation, staging release, and final simulation checkpointing respect the paused flag.

## Planned change

1. Remove `checkpointAllItems(simulationId, event.getTimestamp())` from `SimulationService.processInternalEvent`.
2. Continue updating `SimulationState.lastProcessedTimestamp` for every event before reduction.
3. Keep existing event-scoped movement behavior:
   - scheduled `ItemPositionChangedEvent` values remain authoritative for the affected item;
   - conveyor physics changes checkpoint only occupants of that conveyor;
   - item deactivation checkpoints only that item, except staging items whose position is coupled to the staging FIFO;
   - deleted, exited, or position-deleted items need no retained movement checkpoint.
4. Retain full checkpointing only at observation boundaries:
   - a requested replay or simulation target time;
   - playback handoff or pause boundaries that expose state;
   - multi-simulation run completion;
   - snapshot or graph-state capture that requires materialized hot state.
5. Do not change event timestamps, queue ordering, per-entity ordering, persistence rules, or simulation namespace isolation.

## Correctness invariants

- Every event advances virtual time even when it causes no Redis movement writes.
- An affected item or conveyor is projected to the event timestamp before physics-changing state is modified.
- Unaffected items remain represented by stored progress plus entry timestamp and are projected lazily by `GraphService`.
- Stopped conveyors and deactivated items do not accumulate distance.
- Reactivation never counts stopped or inactive wall-clock/virtual time as movement.
- Fixed-seed multi-simulation runs remain deterministic.
- Simulation events remain excluded from live ClickHouse history.

## Verification

Add or retain Testcontainers-backed integration coverage for:

- an unrelated event leaving another moving item's Redis checkpoint unchanged;
- lazy graph projection still showing that unrelated item at the correct virtual position;
- scheduled item movement producing the same position transitions and final state without global checkpointing;
- item deactivation freezing at the event timestamp and activation resuming from that checkpoint;
- conveyor stop, alarm, speed, length, type, and restart behavior affecting only the appropriate conveyor population;
- stopped and item-paused state surviving unrelated events and final boundary checkpointing;
- staging FIFO release and pause behavior;
- replay target state, what-if playback, and multi-simulation metrics;
- identical results for repeated runs using the same topology, configuration, and seed.

Run the focused simulation, playback, graph movement, alarm, what-if, and multi-simulation integration tests. Compare a representative high-arrival multi-simulation before and after the change, recording processed events per second and run duration. The acceptance criterion is removal of per-event full Redis scans with no change in deterministic final metrics.
