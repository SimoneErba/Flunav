# Interview Bugs

This file is interviewer-only. Do not give it to candidates during the exercise.

## Easy: Position Changes Ignore Simulation Time

### Candidate Symptom

When a position is changed from a simulation request, the generated `ItemPositionChangedEvent` is stamped with wall-clock time instead of the active simulation timestamp. Playback can jump, replayed moves appear out of order, or simulated item state differs from the same event sequence processed through the simulation engine.

### Debugging Hints

- Start from `PositionController.changePosition`.
- Compare how item creation, graph mutations, and scheduled simulation events get their timestamps.
- Look for direct calls to `Instant.now()` in request handlers.
- Check what `TimeService.now()` returns while virtual time is active.

### Root Cause

`PositionController.changePosition` constructs `ItemPositionChangedEvent` with `Instant.now()`. That bypasses the centralized `TimeService`, so simulation-scoped virtual time is ignored.

### Exact Solution

Inject `TimeService` into `PositionController` and replace `Instant.now()` with `timeService.now()` when creating `ItemPositionChangedEvent`.

### Suggested Regression Test

Add a controller or integration test that enters a virtual simulation time with `timeService.enterVirtualTime(fixedInstant)`, posts a position change, captures the processed `ItemPositionChangedEvent`, and asserts that the event timestamp is `fixedInstant`.

## Medium: Deleted Conveyors Leave Redis State Behind

### Candidate Symptom

After deleting a conveyor, items can still appear associated with the deleted conveyor in live state. Redis keys such as `conv:<conveyorId>:items` or `conv:<conveyorId>:tail` remain after the OrientDB edge has been removed. Later movement calculations may read stale queue or tail data for a conveyor that no longer exists.

### Debugging Hints

- Reproduce by placing or moving an item onto a conveyor, deleting the conveyor, and inspecting Redis.
- Compare topology deletion in `ConveyorService.deleteConveyor` with cleanup support in `LiveConveyorRepository`.
- Search for keys ending in `:items` and `:tail`.
- Check whether OrientDB and Redis are kept in sync during deletes.

### Root Cause

`ConveyorService.deleteConveyor` deletes only the OrientDB edge. It no longer calls `liveConveyorRepository.deleteConveyor(conveyorId)`, so the Redis conveyor item set and tail position survive the topology delete.

### Exact Solution

Read the conveyor edge `customId` before deleting the edge, then call `liveConveyorRepository.deleteConveyor(conveyorId)` when the id is present.

### Suggested Regression Test

Add a service-level test that creates a conveyor, writes Redis state through `LiveConveyorRepository.addItemToConveyor` and `updateTailPosition`, deletes the conveyor through `ConveyorService.deleteConveyor`, and asserts that both the item set and tail key are gone.

## Hard: Simulation Replay Writes Through The Live Context

### Candidate Symptom

Processing queued simulation events can mutate live Redis or OrientDB state. A simulation replay that should be isolated may change live item position, conveyor occupancy, metrics, or graph-derived state. Tests that assert simulation virtual clock behavior or live/simulation isolation may fail depending on replayed events.

### Debugging Hints

- Start from `SimulationService.processEventsUntil`.
- Follow `eventProcessor.processEventWithoutBroadcast(event)` into repositories that depend on `DatabaseContextHolder.getSimulationId()`.
- Inspect Redis key prefixes during replay: simulation writes should use `sim:<simulationId>:...`, not live `conv:*` or item keys.
- Compare this method with other historical or simulation builders that explicitly enter simulation context.

### Root Cause

`SimulationService.processEventsUntil` sets virtual time around internal event processing but no longer enters `DatabaseContextHolder.enterSimulationContext(simulationId)`. Repository calls during replay therefore see no simulation id and use live storage.

### Exact Solution

Restore the simulation context scope around internal replay:

```java
try (var ctx = DatabaseContextHolder.enterSimulationContext(simulationId);
        var timeContext = timeService.enterVirtualTime(event.getTimestamp())) {
    eventProcessor.processEventWithoutBroadcast(event);
}
```

### Suggested Regression Test

Use or extend `SimulationPlaybackIntegrationTests#simulationVirtualClockDoesNotMutatePhysicalClock` with an internal movement event that writes repository state. Assert that replay creates or updates only `sim:<simulationId>:...` Redis keys and leaves the corresponding live keys unchanged.
