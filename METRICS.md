# Metrics Plan: Path Traversal Analytics Per Simulation

## Summary

Track path history and path-derived analytics from explicit `PathTraversedEvent` facts instead of relying on `default.Events`. Emit `PathTraversedEvent` whenever an item effectively finalizes or changes its path state, and write those facts into a dedicated ClickHouse ingest table with `ENGINE = Null` so materialized views update metrics without polluting replay history.

Use `simulation_id` on all path and movement analytics tables and materialized views, with live data stored as `simulation_id = 'live'`.

## Implementation Changes

### Path traversal event generation and processing

- Register `PathTraversedEvent` in `DomainEvent` polymorphic deserialization so it is a valid event type everywhere it is handled.
- Stop persisting live `PathTraversedEvent` rows into the `default.Events` event store; treat it as analytics-only, not replay history.
- Add a dedicated analytics reduction path for `PathTraversedEvent` that writes one row to a ClickHouse ingest table such as `analytics_path_traversal_ingest` with `ENGINE = Null`.
- Include in the ingest row:
  `event_timestamp`, `simulation_id`, `item_id`, `previous_position_id`, `previous_position_type`, `new_position_id`, `new_position_type`, `path`.

### New emission points

- `ChuteEmptyEvent` in `EventProcessor`:
  for every item removed from the chute, emit one `PathTraversedEvent` before deleting the item state.
  Use the item’s current location and type as both previous and new position, and `path = [currentLocationId]`, matching the existing delete semantics.
- `ItemPathChangedEvent` in `EventProcessor`:
  after updating the item path, emit one `PathTraversedEvent` for that item using the item’s current position as both previous and new position and the new assigned path as `path`.
- `ItemDestinationEvent` in `EventProcessor`:
  after routing recalculates and persists the selected path, emit one `PathTraversedEvent` for that item using the item’s current position as both previous and new position and the computed path as `path`.
- Keep the existing `ItemDeletedEvent` emission, but route it through the same analytics-only handling path instead of RabbitMQ plus event-store persistence.

### Context and simulation handling

- Resolve `simulation_id` at write time from `DatabaseContextHolder.getSimulationId()`.
- Use `live` when there is no simulation context.
- Do not send simulation-generated `PathTraversedEvent` through RabbitMQ; process it in the current thread and context so the correct simulation namespace is preserved.
- Keep the event immutable and unchanged as a DTO unless an extra field is strictly required; prefer deriving `simulation_id` from context in the analytics writer.

### ClickHouse schema changes

- Create a Null-engine ingest table for path traversal analytics.
- Rebuild `item_journeys` to include `simulation_id` in both schema and sort key.
- Rebuild movement analytics tables to include `simulation_id`:
  `analytics_location_transit_events`,
  `analytics_location_transit_counts`,
  `analytics_path_transit_stats`.
- Update all related materialized views to group by `simulation_id` as part of their aggregation key.
- Update runtime schema initialization in `ClickHouseService.ensureMovementAnalyticsSchema()` and the SQL bootstrap files under `docker/clickhouse/init-clickhouse/` and `backend/src/test/resources/init-clickhouse/`.
- Update diagnostic and example queries in `docker/clickhouse/queries.sql` to filter or group by `simulation_id`, defaulting examples to `live`.

### Query and API behavior

- Any backend query reading movement or path analytics must filter by `simulation_id`.
- Live requests should read `simulation_id = 'live'`.
- Simulation-scoped requests should read the active simulation id from `DatabaseContextHolder`.
- If there is no existing API for path analytics yet, keep the schema and query changes internal and only update the current query helpers and examples that inspect these tables.

## Public Interfaces and Types

- `PathTraversedEvent` becomes an officially registered `DomainEvent` subtype.
- `LocationTransitMetric` should gain a `simulationId` field so raw movement transit facts are written with the same scoping model as path-traversal metrics.
- ClickHouse table contracts change:
  `item_journeys`, `analytics_location_transit_events`, `analytics_location_transit_counts`, and `analytics_path_transit_stats` all add `simulation_id String`.

## Test Plan

- Integration test: live `ItemDestinationEvent` recalculates a path and produces one path-traversal metric row under `simulation_id = 'live'`.
- Integration test: live `ItemPathChangedEvent` produces one path-traversal metric row with the updated `path`.
- Integration test: `ChuteEmptyEvent` emits one path-traversal metric row per removed item and updates `item_journeys` for each item.
- Integration test: `ItemDeletedEvent` still updates path metrics but does not write `PATH_TRAVERSED` into `default.Events`.
- Integration test: movement analytics writes and aggregates are isolated by `simulation_id`, with the same item and path exercised once in live and once in a simulation and verified to land in separate metric groups.
- Integration test: simulation-generated path-traversal metrics are written under that simulation id without leaking into `live`.
- Regression test: replay and history loading are unaffected because `PathTraversedEvent` is no longer relied on as persisted domain history.

## Assumptions and Defaults

- `simulation_id = 'live'` is the canonical live scope.
- `item_journeys` is treated as a metric table and must also be simulation-scoped.
- `PathTraversedEvent` is analytics-only and should not be part of replayed business history.
- Emission on destination and path changes records path assignment changes even when the item has not physically moved yet; this is intentional because the goal is path analytics, not only physical transit analytics.
