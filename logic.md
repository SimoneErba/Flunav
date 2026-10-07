# Airport baggage sorting: core logic readiness

Assessment date: 2026-09-19. Code inspected at commit `d3fcb0b`.

This document assesses the current implementation and proposes the core logic needed to use Flunav for airport baggage sorting. It covers destination correctness, tracking, recovery, deadlines, congestion, equipment interaction, and replay. It is a source review and implementation roadmap; the existing tests were inspected, not executed for this assessment. Proposed behavior below is not implemented by this document.

## 1. Answers to the main questions

Flunav has useful routing, capacity, simulation, and event-processing foundations. It does not yet provide the lifecycle and confirmation logic needed to account for every bag through an airport operation.

| Question | What the code does today | Readiness gap |
| --- | --- | --- |
| Does every bag get a valid destination it can reach? | Creation uses explicit destinations or matching destination rules. Routing expands logical destinations into physical exits, searches usable conveyors, and checks chute capacity. Missing destinations can produce `UNROUTED`; unreachable destinations can produce `FAILED`. | There is no invariant requiring every accepted bag to have either an eligible route or a managed exception with an owner and deadline. Graph reachability alone does not establish flight eligibility. |
| Does a bag that disappears and reappears get the correct destination? | A position update can preserve an existing Redis assignment, and managed decision points recalculate routes. However, `ItemPositionDeletedEvent` deletes both hot state and durable item metadata. Position updates do not reconstruct a complete bag record or resolve its current flight assignment. | Recovery depends on how the disappearance was represented and which state survived. There is no complete reacquisition workflow. |
| When a flight is departing, are all bags sent correctly and on time? | Mapping rules have `validFrom`, `rushAt`, and `validTo`. A matching rush window raises effective priority to `1.0` when routing is evaluated. | There is no first-class flight lifecycle, bag cutoff calculation, forecast of flight completion, or reconciliation of expected bags against confirmed loading. A rush window cannot establish departure readiness. |
| Is routing just the shortest path? | No. Exit selection already considers chute utilization, in-flight assignments, pending urgent demand, and priority. Within each candidate exit, path cost is nominal conveyor travel time plus timed-node delay. | Intermediate queues, merge contention, staging release time, downstream discharge rate, and predicted congestion do not contribute to that path cost. |
| Can the system prove that a bag arrived correctly? | Entering any `CHUTE` sets routing status to `COMPLETED`. Exit events and chute-empty events remove hot state. A cleanup job can infer an exit from age. | Completion does not require confirmation of the intended flight, correct handoff, or aircraft loading. Prediction and observation need distinct meanings. |

The implementable objective is: every accepted bag remains accounted for, receives only eligible routing instructions, and is either confirmed at the required handoff before cutoff or explicitly reported as an unresolved exception. No routing algorithm can guarantee on-time delivery when physical capacity, available time, or equipment availability makes it impossible.

## 2. What already exists, and where its guarantees stop

### 2.1 Destination and route selection

The current flow is:

`item fields -> logical destinations -> candidate physical exits -> usable path -> capacity/priority ranking -> assignment -> movement`

[DestinationMappingService](backend/src/main/java/com/flunav/backend/services/DestinationMappingService.java) evaluates typed rules and validity windows. [DestinationExitMappingService](backend/src/main/java/com/flunav/backend/services/DestinationExitMappingService.java) maps logical destinations to exits. [RoutingDecisionService](backend/src/main/java/com/flunav/backend/services/RoutingDecisionService.java) selects the route.

Existing behavior worth preserving:

- Automatic route search excludes inactive conveyors and conveyors with nonpositive speed.
- Chute demand includes physical occupancy, bags already assigned but not yet present, and a limited allocation for waiting priority bags.
- Priority is a score from `0` to `1`. At `0`, candidate exits are ranked by utilization before travel time. At `1`, nominal travel time wins among capacity-eligible exits. Intermediate values blend both.
- The utilization threshold varies from 90% at priority `0` to the hard capacity at priority `1`; full chutes remain unavailable. Pending reservations are capped using 20% of chute capacity scaled by priority.
- Managed decision points recalculate assignments. Capacity-release events retry bags in `WAITING_FOR_CAPACITY`.
- [RoutingCoordinator](backend/src/main/java/com/flunav/backend/services/RoutingCoordinator.java) serializes capacity-sensitive decisions within each live/simulation context in one JVM.
- [PathCacheRepository](backend/src/main/java/com/flunav/backend/repositories/PathCacheRepository.java) separates live and simulation caches; topology reducers invalidate cached paths on relevant changes.

These are useful heuristics, not a complete reservation or deadline system. Missing/nonpositive chute capacity is treated as unlimited. Physical node eligibility, flight allocation, security clearance, and intermediate resource capacity are not unified routing constraints. The process-local routing lock does not coordinate multiple backend instances.

There are also two path services: [PathfindingService](backend/src/main/java/com/flunav/backend/services/PathfindingService.java) provides topology-oriented paths, while `RoutingDecisionService` applies availability and exit-capacity policy. Airport routing needs one explicit feasibility contract shared by assignment, manual overrides, movement, and recovery.

### 2.2 Congestion is partly handled, but not predicted along the route

`RoutingDecisionService.calculateAvailablePath` uses Dijkstra with `length / speed` plus the target timed-node processing delay. It does not read conveyor occupancy, merge queues, or measured discharge rates when computing that path.

[ItemMovementProcessor](backend/src/main/java/com/flunav/backend/services/ItemMovementProcessor.java) has local accumulation, chute entry guards, and main-path recirculation. [AnomalyEngine](backend/src/main/java/com/flunav/backend/services/AnomalyEngine.java) already detects occupancy jams, pressure, timing anomalies, and invalid/skipped transitions. These observations are useful foundations, but they are not a queue-delay model used by route scoring.

Consequently, a route can be shortest in nominal transit time and still be slowest in practice. Choosing a less occupied exit also does not establish that the path to it is less congested. High priority currently emphasizes nominal speed; it does not estimate the probability of making a flight.

### 2.3 Concrete correctness gaps to address first

The following findings are visible in the inspected code. Their operational consequences should be reproduced in focused integration tests before behavior is changed.

| Area | Code evidence | Consequence for baggage operation |
| --- | --- | --- |
| Lost identity | `EventProcessor`, `ItemPositionDeletedEvent` branch, calls `ItemService.deleteItem`; that method removes Redis state and the OrientDB item. | A tracking failure destroys the current record needed for reconciliation. ClickHouse history may remain, but normal reacquisition does not restore it. |
| Incomplete reacquisition | `ItemPositionChangedEvent` updates position without running destination mapping. `LiveItemRepository.updatePosition` can create a partial Redis hash when hot state is absent. Destinations and selected exits are hot fields; ordinary OrientDB item metadata does not retain them. | A fresh scan after deletion or hot-state loss is not enough to recover the bag's intended assignment. A repeated create is also ignored if the durable item already exists. |
| Stale business assignment | `MapDestinationsEvent` replaces rules; `ItemPropertiesUpdatedEvent` updates metadata. Neither re-resolves destinations for existing bags. `MapDestinationExitsEvent` explicitly retries waiting priority bags, not every affected bag. | Flight changes, corrected bag attributes, and allocation changes can leave existing bags with stale business destinations or routes. |
| Expiry does not close a flight | `evaluateRush` changes effective priority within a window; `selectRoute` continues using the item's stored destinations. No flight-close event or timer is present. | After rule expiry, an existing bag may retain a destination even though new bags would no longer receive it. Crossing `rushAt` does not itself dispatch a stalled bag. |
| Unsafe fallback semantics | `calculateNextConveyor` can choose the main path or first active outgoing conveyor when no assigned path matches. That movement fallback is less restrictive than the non-chute fallback in `RoutingDecisionService`. | An unroutable bag can keep moving without a proven safe holding/recovery destination. Loops and arbitrary exits need explicit policies. |
| Incorrect completion boundary | `processLocationEntry` sets `COMPLETED` for any chute, without matching the selected exit or flight. | Wrong-chute arrival can appear completed. Chute arrival also says nothing about later loading. |
| Assumed exits and age deletion | `StaleItemCleanupService` emits `ItemExitedEvent` for chute residents older than ten minutes, checked every five minutes. Startup cleanup deletes live hot state with position checkpoints older than 24 hours. | Missing evidence can become successful throughput or disappearance from active inventory. Long-held baggage needs an exception lifecycle, not age-based removal. |
| Staging release bypass | `releaseStagingConveyor` uses `calculateNextConveyorIgnoringAvailability`. An existing staging test explicitly expects a planned transition onto an inactive downstream conveyor. | The existing staging primitive cannot be treated as a safe early-bag release policy without a downstream admission check. |
| Unconfirmed equipment commands | `PathAssignmentPublisher` logs publication failures and returns; its message has no command ID, assignment revision, acknowledgement, or expiry. | An internal assignment can appear successful while equipment never receives or executes it. Broker delivery is not evidence of a successful diversion. |
| Durability gap | `EventProcessor` changes state before `ClickHouseService.saveEventAsync` queues history in a bounded in-memory deque. `ItemEventListener` acknowledges after processing completes, without waiting for that queued history to reach ClickHouse. | An abrupt crash can leave acknowledged state changes without recoverable event history. Existing batch retries and insert-ID checks help, but do not close this crash window. |
| Partial restart recovery | `StateRecoveryService` skips restoration if any active Redis items exist, and requires a snapshot for its empty-Redis restore. `LiveMovementRecoveryService` reconstructs conveyor schedules from surviving hot state. | This does not reconcile partial Redis loss, all outstanding deadlines, or physical movement during downtime. Restart recovery and bag reacquisition are different problems. |

Evidence: [EventProcessor](backend/src/main/java/com/flunav/backend/services/EventProcessor.java), [ItemService](backend/src/main/java/com/flunav/backend/services/ItemService.java), [LiveItemRepository](backend/src/main/java/com/flunav/backend/repositories/LiveItemRepository.java), [StaleItemCleanupService](backend/src/main/java/com/flunav/backend/services/StaleItemCleanupService.java), [PathAssignmentPublisher](backend/src/main/java/com/flunav/backend/services/PathAssignmentPublisher.java), [ClickHouseService](backend/src/main/java/com/flunav/backend/services/ClickHouseService.java), [ItemEventListener](backend/src/main/java/com/flunav/backend/messaging/ItemEventListener.java), [StateRecoveryService](backend/src/main/java/com/flunav/backend/services/StateRecoveryService.java), [LiveMovementRecoveryService](backend/src/main/java/com/flunav/backend/services/LiveMovementRecoveryService.java).

The existing [AirportBaggageSimulation](simulator/src/main/java/flunav/simulator/AirportBaggageSimulation.java) is a useful topology and traffic scenario. Its inherited reacquisition scenario sends a new position followed by an explicit path update. It therefore does not establish that backend logic autonomously recovers a lost bag's destination. See `injectReacquiredItem` in [RoutingScenarioSimulation](simulator/src/main/java/flunav/simulator/RoutingScenarioSimulation.java).

## 3. Required domain guarantees

These should be executable invariants, checked by reducers and reconciliation jobs:

1. Every physically accepted bag has a durable journey identity, a responsible operational owner, and a current disposition. Unknown identity or location is an explicit state.
2. Every known bag has a current flight/service assignment or a reason-coded hold/exception. A physical exit alone is insufficient business identity.
3. Every issued route is feasible from the bag's confirmed position or already committed segment, satisfies all eligibility rules, and refers to a specific topology and allocation version.
4. A route is revalidated before a controllable diversion. A bag already past that decision cannot be redirected backward by changing a database field.
5. Every move requiring capacity has admission control. Priority never overrides hard capacity, screening, equipment interlocks, or flight closure.
6. A missing observation never proves successful delivery. Position prediction, commanded movement, observed movement, and custody transfer remain distinguishable.
7. Every bag either has enough predicted time to meet the required cutoff or has an explicit at-risk/missed-cutoff exception and recovery action.
8. Duplicate, late, and retried messages cannot regress authoritative state, double-count a handoff, or reactivate a completed journey accidentally.
9. Every assignment, override, hold, recovery, and handoff has an auditable cause. State can be rebuilt after failure without issuing live commands during replay.
10. No bag leaves accountable inventory through a timeout, topology edit, failed lookup, or cleanup job. Removal requires a recorded custody transfer or explicit resolved disposition.

For inventory reconciliation, classify each accepted journey into exactly one current custody bucket: in this system, transferred out with evidence, or explicitly resolved by an authorized disposition. Bags with unknown location remain in this system's accountable inventory. Expected-but-not-yet-accepted bags belong in a separate flight manifest comparison.

## 4. Core modules to add or extend

These are logical boundaries inside the existing backend architecture; they do not require separate microservices.

### 4.1 Bag identity, lifecycle, and assignment

Extend `ItemService` with a durable baggage journey model. Keep physical position in Redis, but make the information needed to recover routing reconstructible independently of a surviving position hash.

Required data includes:

- Internal journey ID, external tag identifiers and aliases, identity confidence, source system, and tag/journey validity. A tag or flight number alone must not identify all future journeys.
- Flight instance and leg identity, including operational date and origin; transfer/connection information; assignment revision and source.
- Handling class and physical restrictions; authoritative screening/reconciliation status references.
- Last confirmed position and observation time, predicted position separately, tracking confidence, custody, and active exception.
- Current routing intent, selected physical exit, route revision, decision reason, and relevant policy versions.

Use separate dimensions for tracking (`KNOWN`, `SUSPECT`, `MISSING`), routing (`ASSIGNED`, `WAITING`, `NO_ROUTE`), and custody/lifecycle. A bag may be missing while still assigned to a valid flight. Do not force all of these meanings into today's `RoutingStatus` enum.

Duplicate bag creation, a late bag source message, retagging, flight reassignment, reinjection, and a scan before identity data arrives need explicit transitions. Preserve the history of corrected identity links instead of silently merging unrelated bags.

### 4.2 Flight lifecycle and destination eligibility

Introduce a `FlightService` and an eligibility policy used by every route entry point.

Store scheduled/estimated operational times, sortation and loading cutoffs, open/closed/cancelled state, and versioned physical allocations. Model the makeup area—the area where bags are assembled for loading—separately from the aircraft stand, flight, and individual chute.

The eligible destination set must account for flight instance, allocation window at expected arrival, bag handling constraints, authoritative screening/reconciliation decisions, exit availability, and downstream handoff capacity. Route through required processing stages; a shortcut must not bypass an unfinished screening or inspection stage.

When flight times, allocation, cancellation, bag properties, or clearance change, identify affected bags through indexes and emit re-evaluation events. Include waiting, stored, recirculating, and previously assigned bags. For bags committed to an old branch, retain their real location and create a recovery task if the new destination cannot be reached.

Use domain-time events for opening, rush, release, and closing boundaries. Persist/reconstruct their schedules and invalidate old timer revisions when the flight changes. Redis TTL must not be the mechanism that closes a flight or determines replay behavior.

An unknown or conflicting assignment must lead to a designated exception process. An empty candidate set must never authorize an arbitrary chute.

### 4.3 Tracking and reacquisition

Introduce a tracking/reconciliation service around position ingestion. An observation should carry a stable event identity, bag identity evidence, sensor/controller identity, source sequence where available, observation time, receipt time, and quality.

Use the sensor mappings to maintain the next expected observation after every confirmed scan and routing decision. Calculate arrival from the confirmed position, selected route, speeds, and known delays, with a configurable grace period initially set to 10 seconds. Recalculate for route/speed changes and suspend expectations during known stops or indefinite holds. Only real sensor evidence satisfies an expectation; projected movement does not. When overdue, mark tracking as lost, retain identity and assignment, open an exception, and reconcile reservations conservatively. Do not free physical capacity merely because a scanner stopped reporting. A known sensor/feed outage should produce a monitoring fault instead of a flood of individual bag-loss conclusions. See [the sensor tracking plan](sensors.md) for timer isolation, revision checks, reacquisition, and acceptance tests.

Represent sensor availability with an `active` boolean, defaulting to `true` for new sensors and legacy mappings. Use `SensorActivatedEvent` and `SensorDeactivatedEvent`, following the conveyor event pattern. Deactivation invalidates expectations for that sensor and selects the next active sensor on the route where possible; otherwise monitoring becomes unavailable. Reactivation recalculates expectations but never proves a missing bag has been found.

On reacquisition:

1. Resolve the scan to the existing journey; quarantine ambiguous identity matches.
2. Determine whether it is newer authoritative evidence or a late/duplicate observation.
3. Cancel obsolete projected moves and command revisions; reconcile previous location and reservation ownership without assuming unobserved space is empty.
4. Establish the observed position and preserve the missing interval in history.
5. Re-evaluate the current flight assignment, clearance, cutoff, and reachable eligible exits from this position.
6. Persist and issue a new route, or place the bag into a reason-coded recovery workflow. Recalculate its delivery risk.

This must work after a brief missed scan, a topology jump, manual removal/reinsertion, Redis loss, backend restart, and return after an earlier confirmed handoff. A returned bag needs an explicit return/reinjection transition, not silent resurrection by an old message.

The anomaly engine can supply evidence about skipped sensors and invalid paths; an alarm alone does not perform identity recovery or destination correction.

### 4.4 Feasible routing and congestion-aware arrival estimates

Extend `RoutingDecisionService` in two stages: first reject infeasible routes, then rank feasible alternatives.

Hard feasibility checks should include directed connectivity, active locations and conveyors, required processing stages, bag restrictions, valid flight allocation, telemetry freshness, and admission to finite resources. Represent the chosen conveyor IDs as well as locations so parallel edges and physical diverters are unambiguous. Apply the same checks to explicit destinations and manual path updates, with audited handling for exceptional operator actions.

Estimate route time from:

`remaining committed travel + conveyor travel + queue/merge wait + processing wait + storage release wait + downstream handoff time`

Use current occupancy, bags already committed to arrive, measured discharge rates, and equipment status. Historical timing distributions can provide uncertainty and fallback estimates. An idle or stale sensor must not imply zero queue delay. A blocked resource with no defensible reopening estimate is unavailable for a guaranteed-time route.

Start with deterministic, nonnegative queue-delay weights and several feasible alternatives. If time-dependent travel estimates are introduced, validate the path algorithm's assumptions; ordinary static Dijkstra does not model arbitrary future queues or scheduled resource windows. Keep topology caching separate from dynamic cost snapshots, which need a version and freshness limit.

Ranking should prioritize eligibility and cutoff feasibility, then delivery risk and resource pressure. Add a switching threshold and minimum stability interval to avoid sending bags back and forth between nearly equal routes. Replan at physical decision points and meaningful state changes, while respecting committed segments.

Maintain an explainable decision record: candidates rejected and why, chosen exit, route, ETA range, cutoff slack, input versions, and reservations. No machine-learning component is required for the first usable implementation.

### 4.5 Capacity, merges, recirculation, and early-bag storage

Replace implicit counts alone with explicit reservation ownership where contention matters. A reservation needs bag ID, resource, assignment revision, state, and release/reconciliation rules. Selection and admission must be atomic within the relevant resource scope. Multiple backend instances require coordinated ownership or an atomic shared operation; the current JVM lock is insufficient.

Account for conveyor occupancy and bag spacing, merge throughput, diverter availability, screening queues, chute discharge, and finite storage. A chute reservation for a bag far upstream is different from permission to enter the next conveyor. Use bounded lookahead and avoid reserving an entire network indefinitely.

Keep decision latency bounded as inventory grows. Today, `countItemsAssignedToExit` scans active bags, and pending-demand allocation scans active state and loads waiting bags' metadata during routing. Maintain context-aware indexes and counters for exit assignments, flight membership, and waiting demand, with reconciliation against authoritative records. Benchmark the complete decision and command path under peak inventory and concurrent arrivals.

Merge scheduling should consider deadline slack and fairness while preserving physical FIFO where bags cannot overtake. Detect spillback and circular waits; stop or meter upstream induction before all recovery routes become full.

Recirculation needs a proven loop, maximum laps/dwell, renewed flight eligibility, and a reachable exception exit. At the limit, create a recovery task. Do not circulate indefinitely or increase traffic in a blocked loop.

Extend staging into early-bag storage with per-bag inventory, planned release times, retrieval lead times, and downstream admission. Release only the appropriate bags when their flight is eligible, and recalculate after schedule changes. A priority change cannot extract a physically inaccessible bag from the middle of a FIFO lane.

### 4.6 Deadline and departure coordination

Introduce a deadline service that schedules bag actions and maintains a flight-level view of remaining work.

Use the operational handoff/loading cutoff supplied by the airport/airline workflow, not scheduled takeoff alone. Either estimate all the way to loading, or subtract the remaining transport/loading allowance from the sortation cutoff; do not count the same allowance twice.

For each bag:

`slack = required completion cutoff - current domain time - estimated remaining duration - uncertainty allowance`

Use a conservative, calibrated estimate for deadline decisions. Store the estimate's confidence and data freshness. Prioritize feasible urgent bags by slack with fairness controls, while keeping all eligibility constraints. Negative slack must create an at-risk or missed-cutoff disposition; making every late bag priority `1.0` does not create capacity.

At flight level, compare expected bags with accepted, held, moving, missing, delivered-to-makeup, loaded, offloaded, and reassigned bags. Forecast whether remaining throughput can clear the flight before cutoff. Raise an actionable intervention when it cannot, with bag IDs and the responsible bottleneck.

Flight closure must fence stale commands, stop new assignments to that flight's allocation, and give outstanding bags an explicit disposition. Reopening, delay, cancellation, reassignment, and offload must be versioned transitions. Expired flight space must not be reused while old committed bags can still arrive without detection and recovery.

### 4.7 Command execution and physical confirmation

Extend `PathAssignmentPublisher` and add a controller-facing command lifecycle. The existing [OPC gateway](opc-gateway/src/main/java/com/flunav/gateway/service/OpcIngestionService.java) ingests observations; it does not provide an end-to-end diversion acknowledgement workflow. Its failure fallback currently logs a dropped event rather than durably buffering it.

Each operational instruction needs a stable command ID, bag and assignment revision, target decision point, preconditions, valid time/position window, and expected acknowledgement. Persist command intent durably with the decision through an outbox or equivalent recoverable protocol. Retry idempotently and reject stale revisions at the receiver.

Track accepted, rejected, expired, executed, and physically confirmed outcomes. An acknowledgement that a controller received a command is distinct from a downstream scan showing the bag actually took the branch. Missing acknowledgement, missed diversion, wrong-chute detection, and disconnected controllers must feed recovery logic.

Define a clear ownership boundary: Flunav owns routing intent, eligibility, and reconciliation; equipment controllers own real-time actuation and local interlocks. Agree on a safe local behavior when supervisory commands are late or unavailable.

Keep simulated/predicted movement separate from physical evidence. `LiveSystemScheduler` currently feeds projected position events into live event processing. For an airport control mode, prediction can drive expected-arrival timers and visualization, but must not by itself confirm location, custody, or aircraft loading. Separate these semantics explicitly instead of relying only on `simulation.manage-logic` or broadcast flags.

### 4.8 Handoff and reconciliation

Introduce confirmed milestones: correct sortation exit, makeup handoff, container/cart association where used, aircraft loading, and offload/return. Consume authoritative loading and reconciliation outcomes from the responsible systems if Flunav does not own that part of the process.

On chute arrival, compare actual location with the active assignment and flight allocation. Wrong-chute arrival opens a misroute exception. Chute clearing releases capacity but does not automatically prove that every bag was loaded. A batch handoff needs an explicit membership list and evidence; repeated messages must not count twice.

IATA identifies acceptance, loading, transfer, and arrival as the four core baggage tracking points. That supports a custody-oriented event model extending beyond conveyor positions. The system boundary should state which points Flunav records and which it receives from partners. [IATA baggage tracking](https://www.iata.org/en/programs/ops-infra/baggage/baggage-tracking/).

Loading evidence and passenger/baggage reconciliation are distinct concerns in IATA's implementation guidance. Flunav should consume the applicable authoritative permission and offload decisions, rather than infer them from a generic flight property. [IATA Resolution 753 implementation guide](https://www.iata.org/contentassets/5c4aa8b8b3b1432697d2bf3301450684/reso753-implementation-guide---2023_issue-4.0.pdf).

### 4.9 Event durability, recovery, and replay

Keep the repository's event-driven architecture. New state changes belong in immutable, versioned events in `commons`, reduced through `EventProcessor`, with context-aware repositories.

Close the acknowledgement/history crash window with a durable ingress journal/inbox and durable command outbox, or an equivalent explicitly designed protocol. ClickHouse can remain the historical and analytical store, but an in-memory queue cannot be the only copy after upstream acknowledgement. Record projection progress so partial OrientDB/Redis writes can be retried or repaired. Do not assume a transaction spans all stores and RabbitMQ.

Deduplicate before effects, using stable source event IDs and semantic identities where sources retransmit with new IDs. Define handling for out-of-order observations, clock skew, conflicting sources, and late corrections. The current per-entity chain preserves local submission order; it does not establish global event-time order or multi-instance ownership.

Persist flight timers, reservations, pending commands, and recovery cases, and reconcile them at restart before issuing fresh commands. Handle partial Redis loss, not only an entirely empty active set. Recover unresolved journeys from durable records/history instead of expiring them. Reconcile with current equipment observations before assuming downtime movement happened as predicted.

For historical replay, use recorded decisions and the versions of topology, allocation, policy, and observations that caused them. For what-if simulation, explicitly recompute decisions under the scenario policy. Preserve simulation namespaces and virtual time across asynchronous work, and suppress all live equipment side effects in both cases.

Mapping storage deserves special attention: `DestinationMappingService` uses physical-clock TTLs and clears the old table before setting the replacement. Flight policy should use versioned atomic replacement and domain-time validity, so a wall-clock expiry or transient empty table cannot change a replay or assignment.

## 5. Integration contracts and proposed events

The minimum external inputs are authoritative bag identity/itinerary updates, flight/allocation changes, screening and loading eligibility decisions, scanner observations, equipment availability, command results, and custody/loading confirmations. Define an owner, revision ordering, freshness limit, and behavior during outage for each feed.

Build adapters around a stable internal model. Match the airport's actual baggage messaging interfaces; IATA's BIX provides a standardized exchange model, alongside the existing baggage messaging practices an integration may need to support. Choosing a format does not supply the routing or recovery logic by itself. [IATA BIX](https://www.iata.org/en/programs/ops-infra/baggage/baggage-information-exchange-bix/), [IATA baggage standards](https://www.iata.org/en/programs/ops-infra/baggage/standards/).

Candidate additive event families, with final names/schema to be designed:

| Concern | Proposed events |
| --- | --- |
| Identity and assignment | `BagRegisteredEvent`, `BagIdentityLinkedEvent`, `BagFlightAssignedEvent`, `BagEligibilityChangedEvent` |
| Flight and allocation | `FlightUpdatedEvent`, `FlightAllocationChangedEvent`, `FlightCutoffReachedEvent`, `FlightClosedEvent` |
| Tracking and exceptions | `BagObservedEvent`, `BagTrackingLostEvent`, `BagReacquiredEvent`, `BagExceptionOpenedEvent`, `BagExceptionResolvedEvent` |
| Routing and resources | `BagRouteAssignedEvent`, `BagRouteInvalidatedEvent`, `CapacityReservedEvent`, `CapacityReleasedEvent`, `BagReleaseDueEvent` |
| Equipment execution | `RouteCommandIssuedEvent`, `RouteCommandAcknowledgedEvent`, `RouteCommandFailedEvent`, `DiversionConfirmedEvent` |
| Custody and deadlines | `BagHandoffConfirmedEvent`, `BagLoadedEvent`, `BagOffloadedEvent`, `BagDeadlineRiskChangedEvent` |

Events need stable IDs, correlation/causation IDs, event and receipt times, relevant entity revisions, and provenance. Decisions should carry enough input/version information to explain their result without consulting today's mutable state.

Use additive contracts and explicit compatibility handling for historical rows. In particular, an old inferred `ItemExitedEvent` must not be upcast into proof of aircraft loading. Legacy history may need an explicit unknown/unconfirmed evidence classification.

## 6. Implementation order and completion gates

| Phase | Scope and main code touchpoints | Gate before proceeding |
| --- | --- | --- |
| P0: Preserve accountability | `ItemService`, `EventProcessor`, cleanup/recovery services, tracking lifecycle, durable ingestion and command intent. Separate observations from projections; retain missing bags; validate chute completion; define physical command ownership. | Lost observations, restart, redelivery, and publication failure cannot silently erase a bag, invent a successful handoff, or lose an acknowledged command/event. |
| P1: Make assignments correct | Flight/journey model, eligibility policy, versioned destination mappings, safe no-route handling, reacquisition, closure/reassignment, and affected-bag re-evaluation. | Every accepted bag is either validly assigned or in an owned exception. No expired, incompatible, or unauthorized destination can receive a new valid command. |
| P2: Make delivery time and flow explicit | Congestion costs, ETA/slack, admission/reservations, merge scheduling, storage release, bounded recirculation, and flight backlog forecasting. | Agreed peak-load and disruption scenarios meet delivery targets or raise a timely actionable exception; hard capacities and physical constraints hold. |
| P3: Prove the full operating workflow | Controller acknowledgement and scanner confirmation, partner custody/loading feeds, complete recovery/replay, operational reconciliation, shadow operation followed by a bounded live pilot. | A full flight bank can be reconciled bag by bag, including failures, manual recovery, and all outstanding exceptions. |

Command acknowledgement and confirmation contracts must be designed in P0; P3 completes and validates their deployment integrations. Do not defer physical command correctness until after enabling live control. Modules can be developed incrementally, but airport readiness requires all gates.

## 7. Required verification

Use the existing Testcontainers integration style with real Redis, OrientDB, ClickHouse, and RabbitMQ. Use real controller/test-bench integration for equipment guarantees; passing backend simulations alone does not prove physical diversion behavior.

| Scenario | Required result |
| --- | --- |
| Missing, conflicting, or late destination data | Bag stays identifiable and enters a bounded exception/hold; newly valid data triggers assignment. |
| Only unreachable, inactive, closed, or ineligible destinations exist | No arbitrary exit command; a reason-coded exception and safe hold/recovery action. |
| Congested short path versus available longer path | Route ranking reflects measured/predicted delivery time and deadline risk, with an explainable decision. |
| Multiple bags claim the final slot concurrently, including across backend instances | No double reservation or capacity violation; waiting bags remain accountable. |
| Bag misses a scan and reappears elsewhere | Same journey and history survive; route is recomputed from confirmed location using current eligibility. |
| Bag reappears after flight closure, cancellation, or reassignment | Old destination/command is rejected; recovery follows the new flight/disposition. |
| Redis routing state is lost but item metadata survives | Identity and assignment recover without relying on a duplicate create or manual path injection. |
| Duplicate or out-of-order scan, exit, load, or acknowledgement | No state regression, duplicate handoff count, or accidental resurrection. |
| Mapping opening/rush/closing boundary passes without a new scan | A durable timer triggers the appropriate re-evaluation; stale timer revisions do nothing. |
| Flight is delayed, advanced, or moved to another makeup allocation | All affected bags, including storage and committed routes, are re-evaluated with explicit recovery for unreachable new allocations. |
| Full chute, jammed merge, or unavailable recovery loop | Admission stops unsafe release; bounded recirculation or owned recovery replaces endless looping. |
| Staging release toward an inactive/full downstream resource | Bags remain held until admitted; release is idempotent and physically FIFO where required. |
| Command is lost, delayed, duplicated, rejected, or applied after reassignment | Retry/recovery is visible; stale commands cannot act; equipment outcome is reconciled. |
| Bag reaches the wrong chute | Misroute exception; no successful completion or loading confirmation. |
| Bag remains at a chute past ten minutes or in storage past 24 hours | It remains accounted for; age alone cannot complete or delete it. |
| Crash between state update, history persistence, broker acknowledgement, and command send | Durable recovery converges without missing events or duplicated physical effects. |
| Partial store failure, controller disconnect, or restart with outstanding deadlines | Defined degraded behavior, preserved inventory, and reconciliation before normal control resumes. |
| Historical replay and future/what-if simulation | Deterministic intended semantics, isolated state, and zero live equipment commands. |
| Flight closes with unlocated or unconfirmed bags | Bag-level discrepancy list and assigned recovery actions; flight totals cannot hide missing bags. |

Existing tests provide starting coverage, not evidence that the new guarantees already hold:

- [GraphServiceItemTests](backend/src/test/java/com/flunav/backend/GraphServiceItemTests.java): destination matching, unreachable destinations, priority/capacity rules, reservations, rush windows, decision-point rerouting, staging, and simulation isolation.
- [ItemPathUpdateIntegrationTests](backend/src/test/java/com/flunav/backend/ItemPathUpdateIntegrationTests.java): directed manual-path validation and event recording.
- [LiveMovementRecoveryIntegrationTests](backend/src/test/java/com/flunav/backend/LiveMovementRecoveryIntegrationTests.java): movement schedule reconstruction from persisted hot state.
- [RabbitEventDeliveryIntegrationTests](backend/src/test/java/com/flunav/backend/RabbitEventDeliveryIntegrationTests.java): processing failure and dead-letter handling.
- [SimulationPlaybackIntegrationTests](backend/src/test/java/com/flunav/backend/SimulationPlaybackIntegrationTests.java) and [AnomalyEngineIntegrationTests](backend/src/test/java/com/flunav/backend/AnomalyEngineIntegrationTests.java): replay/isolation and anomaly behavior to preserve.

Define quantitative acceptance targets from the intended installation: bags per second, flight-bank peaks, resource capacities, route-decision latency, maximum tolerated observation/command delay, recovery time, ETA error, on-time handoff rate, and maximum unresolved-bag age. Route computation plus transport and controller latency must fit the actual time before a bag passes its decision point. Measure those limits under load and equipment/data-feed failures.

The immediate priorities are durable bag accountability, current flight eligibility, confirmed physical outcomes, and recovery. Congestion-aware path costs and deadline scheduling then turn those correctness foundations into timely delivery.
