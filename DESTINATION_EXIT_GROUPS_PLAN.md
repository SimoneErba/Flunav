# Generic Destinations and Exit Groups

## Summary

- Replace an item's physical `destinationId` with ordered logical `destinations: string[]` and derived `selectedExitId`.
- Add Redis-backed mappings from logical destinations to ordered physical exit IDs.
- Route to the first reachable exit, checking destinations and their exits in configured order.
- Preserve old events, snapshots, Redis hashes, simulator messages, and CSVs.

## Contracts

- Item requests, responses, and WebSocket payloads expose `destinations` and nullable `selectedExitId`; clients cannot directly set the selected exit.
- Existing property mappings change from `destination: string` to `destinations: string[]`. Matching rules merge their destinations in row order, removing duplicates.
- Add admin `GET/PUT /api/destination-exit-mappings` with records shaped as `{ destination: string, exits: string[] }`.
- Keep `ItemDestinationEvent.locationId` as the physical-exit command contract; processing it updates `selectedExitId` and the path without replacing logical destinations.
- Accept legacy `destinationId` and single `destination` inputs, but emit only the new API shape.

## Implementation

- Store item destinations as JSON in Redis hash field `ds`; retain `d` for `selectedExitId`. Reading an old `d` value produces a singleton destination and direct selected exit.
- Store exit mappings under a simulation-namespaced Redis table such as `destination_exit_map:records`. PUT replaces the table, and an empty list clears it.
- Validate nonblank names and exits, nonempty exit lists, unique destination rows, and ordered deduplication. Permit references to locations that have not been created yet.
- Resolve explicit item destinations before property mappings. For each destination, test mapped exits in order and select the first existing, reachable exit; continue to later destinations when necessary.
- For compatibility, an unmapped destination matching an existing location ID is treated as a direct exit.
- Leave destinations stored but `selectedExitId` and path unset when no candidate is reachable or the item has no current position.
- Add the requested routing comment where candidates are selected: ordered first-reachable selection is temporary, and future routing should score every compatible exit by convenience such as capacity and congestion.
- Use `selectedExitId` for path regeneration, RabbitMQ commands, graph responses, snapshots, and WebSocket updates. Mapping changes do not reroute existing items automatically.
- Add `MapDestinationExitsEvent`; validate mapping events before processing and record successful mapping replacement events so simulations can restore the latest mapping state before a snapshot and replay later changes.
- Extend ClickHouse snapshot JSON additively with `destinations` and `selectedExitId`, retaining legacy `destinationId` for old rows. Normalize old snapshots during restoration.
- Update the simulator and regenerate the checked-in TypeScript API client.
- Split the admin mappings page into property-to-destination and destination-to-exits sections. Both support add, delete, reload, import, review, and save.
- Existing CSV header becomes `fieldName,dataType,operator,value,destinations,validFrom,validTo`; continue accepting legacy `destination`.
- New CSV uses `destination,exits`, with JSON arrays such as `A,"[""exit-1"",""exit-2""]"`. Show logical destination suggestions from configured groups and exit suggestions from graph locations.

## Test Plan

- Integration-test mapping replacement, clearing, validation, ordering, legacy decoding, and live/simulation Redis isolation.
- Verify explicit and property-derived destination arrays, merged rule results, first-reachable selection, later-goal fallback, unreachable candidates, and direct legacy exits.
- Verify physical commands contain `selectedExitId`, preserve logical destinations, and safely handle missing paths.
- Test old `ItemCreatedEvent`, Redis hash, and snapshot payload compatibility plus new ClickHouse snapshot round trips.
- Update the graph WebSocket E2E test to configure destination-to-exit mappings and assert `destinations`, `selectedExitId`, and path.
- Add an admin E2E check for JSON-array CSV import and persistence.
- Run focused backend integration tests, snapshot/replay tests, simulator compilation, frontend build, E2E typecheck, and focused Playwright tests.

## Assumptions

- Destination and exit ordering is significant.
- Destination-to-exit mappings are static and have no validity dates; property mappings retain their current validity windows.
- Logical names and location IDs remain case-sensitive after trimming.
- Dynamic "most convenient exit" scoring and automatic rerouting are deferred.
