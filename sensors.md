# Sensor mapping, expected arrivals, and tracking recovery

## Summary

Add an admin-only Sensors page for managing a complete sensor-to-conveyor mapping table. Each mapping has `sensorName`, `conveyorId`, `progress` as a percentage from 0 to 100, and an `active` boolean. Position events keep accepting direct topology IDs; when an ID is unknown, the event reducer resolves it as a sensor name and uses the mapped conveyor and progress.

Use these mappings to predict the next sensor observation for each item. If no qualifying observation arrives by the expected arrival time plus a configurable grace period of 10 seconds by default, mark the item's tracking as lost. Preserve its identity, routing information, and last confirmed position so a later observation can recover it. This status means the current position is unknown; it does not prove physical loss or successful exit.

## Key changes

- Add immutable shared `SensorMappingRecord` and `MapSensorMappingsEvent` contracts, register the event for Jackson and ClickHouse replay, and add a simulation-namespaced `SensorMappingService`.
  - Replacing the mapping table is atomic and replayable.
  - Require nonblank sensor and conveyor IDs, a finite progress value in `[0,100]`, an existing conveyor in the active topology, and case-insensitively unique sensor names.
  - Use only `active: true/false` for sensor availability, matching the conveyor model. New sensors and legacy mapping records that omit the field default to `true`; API responses and newly saved mappings include it explicitly.
  - Preserve raw sensor IDs in stored position events; resolve them during reduction so RabbitMQ ingestion, historical replay, and simulations all use the mapping state that existed at that event time.
  - A direct topology ID takes precedence over a sensor alias. An unknown ID continues through the current failure path.

- Add `SensorActivatedEvent` and `SensorDeactivatedEvent`, matching the conveyor activation/deactivation event pattern. They identify the sensor by `sensorName` and carry the standard event ID and timestamp; no sensor status enum is introduced.
  - The PLC/gateway reports an unavailable sensor with `SensorDeactivatedEvent` and its recovery with `SensorActivatedEvent`. Admin edits may also change `active` through the mapping table.
  - Apply both event types through `EventProcessor`, persist them for replay, and update the active simulation namespace. Reject unknown sensor names and make repeated activation/deactivation harmless.
  - Restore activation changes as well as mapping changes around snapshots, and include `active` in snapshots and WebSocket updates.

- Add `GET` and admin-protected `PUT /api/sensor-mappings`. The PUT endpoint replaces the full table by processing `MapSensorMappingsEvent`; `ADMIN` and `SUPERADMIN` may manage mappings.
  - Extend historical restoration to load the last sensor-mapping event before a snapshot, then replay later mapping changes in timestamp order.
  - Include all configured sensor mappings and their `active` flags in `GraphData` so live, design, historical, and simulation graph views receive the correct marker configuration, including inactive sensors.

- Add an `/admin/sensors` workspace with a four-column editable table, including an Active checkbox, reload, add/delete row, save, CSV import, and CSV export.
  - CSV exports use the exact header `sensorName,conveyorId,progress,active`, with `true`/`false` values. Also accept legacy three-column imports, defaulting their rows to `active=true`; imports are reviewed in the table and saved explicitly.
  - Use a conveyor-ID datalist and client validation alongside the server validation.
  - Add a Sensors navigation entry alongside Mappings in the live, admin, and assistant headers, and regenerate the TypeScript OpenAPI client for the new contract.

- Render each mapping as a non-interactive, small triangle node in Sigma.
  - Give markers collision-safe IDs such as `sensor:<sensorName>`, place them by interpolating from the conveyor source to target with `progress / 100`, and orient them toward the conveyor target.
  - Register a small local triangle WebGL node program, display the sensor name as its label, and exclude sensor markers from item hover, location editing, edge creation, and dragging.
  - Keep inactive sensor markers visible with a muted appearance and an inactive indication.

## Expected sensor arrivals and tracking loss

### Create and maintain the expectation

- After every confirmed sensor detection, identify the next active sensor ahead on the item's committed segment and selected route. Re-evaluate at routing decision points and when the route changes. Include sensors on the current conveyor using their mapped progress; do not expect the sensor just passed again unless the route loops back to it.
- Calculate `expectedArrivalAt` from the last confirmed observation time, remaining conveyor distances and speeds, and known processing, queue, and staging delays. Set `deadline = expectedArrivalAt + gracePeriod`, initially using a backend-configured grace period of 10 seconds.
- Store one current expectation per item, including the expected sensor, arrival time, deadline, last confirmed observation, and an expectation revision tied to the route and mapping used. If there is no known next sensor or no defensible arrival estimate, expose an unmonitored/uncertain reason instead of inventing a deadline.
- Recalculate on speed, route, mapping, or known congestion changes. Suspend the affected expectation during a known stop or indefinite staging hold, then recalculate when movement resumes. Graph refreshes and predicted movement alone must not keep extending the deadline. A new estimate must not silently clear an existing tracking-loss incident.

### Confirm observations and handle timeouts

- Preserve observation provenance before resolving sensor aliases. Only an actual item-identifying observation can satisfy a sensor expectation. Internally scheduled `ItemPositionChangedEvent` projections, manual positioning, and graph interpolation cannot confirm a sensor read; direct topology position updates need explicit sensor evidence to do so.
- Keep tracking status separate from `RoutingStatus`. At timeout, process a replayable tracking-loss event through `EventProcessor`, conditional on the expectation revision still being current and unsatisfied. Emit the transition once and expose the missed sensor, deadline, and last confirmed position.
- Never use `ItemPositionDeletedEvent` for a missed sensor deadline: its current reducer deletes item metadata. Retain the item and its assignment, stop presenting predicted movement as confirmed location, and do not infer an exit or release physical occupancy merely because tracking was lost. Cleanup must preserve unresolved items.
- Give tracking timers their own scheduling identity. `LiveSystemScheduler` currently keeps one scheduled task per entity, so a sensor timeout must not replace that item's movement event or be cancelled when its movement is rescheduled. Serialize timeout and observation effects through the item's event-processing order and recheck the expectation before changing status.
- When `active` becomes `false`, invalidate expectations for that sensor so their old timers cannot mark items lost. Select the next active sensor ahead on the planned route and calculate its expectation; if none is available or timing is uncertain, expose monitoring as unavailable. Report the inactive sensor once rather than creating an individual bag-loss incident for every affected item. A missed bag observation must not itself deactivate the sensor.
- When `active` becomes `true`, reconcile observations and recalculate affected expectations. Activation alone must not mark a missing bag as found or count as a confirmed arrival. Feed diagnostics can emit activation/deactivation events for affected sensors without adding a separate sensor status model.

### Reacquisition, persistence, and display

- A newer confirmed observation at any mapped sensor establishes the item's observed position, even if the expected sensor was skipped. Cancel obsolete expectations and projected movement, preserve the missed-observation history, and clear tracking loss through a replayable reacquisition transition. Revalidate the destination and route from the observed position using current routing rules, then arm the next expectation. Record an unexpected route/scan as an anomaly instead of treating it as proof the old route was followed.
- Deduplicate observations and reject stale observations as updates to current position. Retain late evidence for audit; an old scan received after the deadline must not make the bag appear currently located at an obsolete position. If identity cannot be resolved, retain an explicit exception rather than constructing a new unrelated bag.
- Persist tracking state and expectations in the active Redis namespace and include them in snapshots/recovery. Add immutable tracking transition events in `commons`, with stable event identities and expectation revisions, and reduce them through `EventProcessor`. Rebuild outstanding timers after restart without resetting their original grace periods.
- Use domain time for expected arrivals and timeout events. Replay restores the recorded tracking transitions; simulation evaluates its own expectations in virtual time without touching live state or issuing live commands. Synthetic sensor observations in a simulation must be explicit scenario inputs, distinct from motion projections.
- Extend item responses, snapshots, and WebSocket updates with tracking status, last confirmed position/time, expected sensor, arrival deadline, and monitoring-unavailable reason. Regenerate the API client. Show tracking-lost items with a clear last-known/unknown-position indication instead of removing them or presenting an estimated position as confirmed.

## Test plan

- Backend integration coverage for mapping validation, case-insensitive duplicate rejection, direct conveyor positioning, sensor-based positioning at the configured progress, and unknown sensor failure without changing item state.
- Verify the `active` default for legacy records, explicit boolean round trips, activation/deactivation events, unknown sensor rejection, repeated-event handling, and restoration of activation state in snapshots and historical replay.
- Replay/simulation coverage proving that a raw sensor position event after a snapshot resolves using the sensor mappings restored for that simulation’s historical timestamp.
- API security coverage for admin access and viewer rejection.
- Frontend browser coverage for the Active checkbox, four-column CSV import/export, legacy three-column import, and save/reload, plus graph assertions that sensor triangles appear at the expected interpolated conveyor coordinates, remain non-editable, and stay visible when inactive.
- Testcontainers-backed tracking coverage for arrival-time calculation across partial conveyors, timed nodes, multiple sensors, and decision-point route changes; no-next-sensor and unknown-delay cases remain explicitly unmonitored.
- Verify the default 10-second grace period and its configuration, an observation received before the deadline, loss when the deadline expires without an observation, and exactly one loss transition for repeated timeout delivery.
- Verify that speed changes, stops/resumes, known queues, and staging holds update or suspend expectations, while graph refreshes and internally scheduled movement cannot confirm a sensor read or indefinitely postpone a timeout.
- Verify that obsolete timeout revisions are harmless after rerouting, remapping, or a newer observation, and that movement timers and sensor timers coexist without cancelling each other.
- Verify that timeout preserves metadata, routing information, and last confirmed position, and that cleanup cannot silently delete the unresolved item or count it as exited.
- Verify reacquisition at the expected sensor and at another sensor, route revalidation from the observed position, preserved incident history, and duplicate/late observations that cannot regress position or clear loss using stale evidence.
- Verify deactivation cancels affected expectations without a flood of individual lost-item transitions, chooses the next active sensor where possible, and exposes unavailable monitoring otherwise. Verify reactivation recalculates expectations without automatically recovering lost bags.
- Verify restart recovery with an outstanding or overdue expectation, replay of tracking transitions, and simulation isolation with explicit synthetic sensor observations. Advance domain time deterministically rather than relying on long sleeps.
- Verify that the UI distinguishes tracking loss and unavailable monitoring from routing failure, and displays the last confirmed position and missed deadline without hiding the item.

## Assumptions

- `progress` is a percentage: `20` means 20% along the conveyor.
- Sensor mappings apply only to conveyors, not locations.
- Sensor names are case-insensitive and must be unique.
- Sensor triangles appear in every graph view using that view’s live or simulation configuration.
- Each confirming sensor observation identifies the item, either directly or through an established correlation mechanism. A bare photocell trigger cannot confirm a specific bag without that correlation.
- The 10-second grace period is an initial configurable tolerance to validate against observed transit variation and message delays, not a guarantee of physical loss at timeout.
- Sensor availability is a single `active` boolean, updated through activation/deactivation events or an admin mapping edit. A missed bag deadline establishes tracking uncertainty and never automatically changes that boolean.
