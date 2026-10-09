# Live system implementation of a real airport

This document records the production baggage-sorting behavior examined in the supplied Charles de Gaulle C# and SQL sources, and how it compares with Flunav. It describes the available source, not a verified deployment configuration or a complete specification of the airport system.

## Sources and scope

| Source | Responsibility |
| --- | --- |
| [source-01.cs.enc](source-01.cs.enc) | Common sorting workflow, identification, flight allocation handling, timing, destination selection, instructions, and result processing |
| [source-02.cs.enc](source-02.cs.enc) | Charles de Gaulle operating modes, security behavior, relabeling, and recirculation overrides |
| [source-03.cs.enc](source-03.cs.enc) | SQL Server data access implementing `ISortingDAL`, using Dapper, views, stored procedures, and configuration caches |
| [source-04.sql.enc](source-04.sql.enc) | SQL definition of `Sorting_GetIntermediateTargets` |

These source files are encrypted. See [the docs README](README.md) for the restore commands.

The source was examined on 29 September 2026. Missing workers, models, services, SQL definitions, and equipment protocol documentation limit some conclusions. No production database or equipment was accessed. The Java comparison describes the repository code examined at that time.

## Terminology and identity

- **DP: decision point.** A point where the host receives a destination request and selects sorting destinations.
- **TP: tracking point.** A configured tracking identifier used throughout the topology. A DP can be associated with a TP; not every TP is necessarily a decision point.
- **BSM: Baggage Source Message.** Airline baggage information associating a bag tag/barcode with flight and travel details. The supplied code consumes BSM records; the upstream message producer/parser is outside these files.
- **EBS: Early Baggage Storage**, also called an early bag store. A waiting area for bags whose flight sorting destination cannot accept them yet. This is the standard airport term; see [Vanderlande's baggage storage description](https://www.vanderlande.com/airports/systems/).
- **MES:** The manual coding/intervention flow used by this code for unresolved identification, relabeling, and other configured reasons. The exact product-specific expansion is not established by the supplied sources.
- **OCR:** The recognition flow used to recover unresolved bag identification.
- **PLC:** Equipment controller receiving sorting instructions and reporting tracking/sort results.

The barcode is an external bag identity. The internal item ID identifies the tracked record. VID and control IDs also participate in tracking and reconciliation; their complete lifecycle requires the missing tracking/message code.

The normal association chain is:

```text
Barcode → BSM → flight and allocation → eligible final destinations
        → next destination selected at the current DP
```

A readable barcode without a matching BSM is `NoBsm`: identity is available, but normal flight-routing information is missing. A BSM does not directly encode a permanent conveyor path. Its flight, destination, class, connection, and exceptions are inputs to allocation and handling rules.

## Overall sorting workflow

`SortingCDG.CalculateDestinations` runs CDG preprocessing, delegates to `SortingBase.CalculateDestinations`, and then modifies the returned destinations where CDG requires it.

The base workflow is:

1. Load sorting edges for the requesting equipment component and PLC source node.
2. Handle simple single-exit points and conditions requiring an empty/no-sort instruction.
3. Update tracking and recirculation state, and apply configured security initialization/reset behavior.
4. Apply special operating-mode behavior and preserve active non-overridable destinations.
5. Resolve identification, BSM, flight, allocation, and timing when normal recalculation is applicable.
6. Apply security routing where the DP requires it.
7. Transform destinations through emergency plans and recirculation handling, and maintain item destination records.
8. Filter configured unavailable final destinations and select directly reachable or intermediate targets.
9. Order selected targets and construct the destination response, using defaults where applicable.
10. Record tracking information. Separate PLC results later establish the physical outcome.

This is a repeated decision process. The intended destination and next commanded exit can change as identification, allocations, time, security results, equipment state, and recirculation change.

### Three destination lists

| List | Meaning |
| --- | --- |
| `FinalTargets` | Intended ultimate destinations, including production and special exits |
| `EBSTargets` | Waiting-area destinations selected by EBS handling |
| `SelectedTargets` | Destinations to command at the current DP |

These are alternatives and operational goals, not an explicit ordered list of every conveyor the bag will traverse.

### DP contexts

| Context | Meaning | Selection behavior |
| --- | --- | --- |
| `I` | Intermediate | Ask for intermediate targets toward final/EBS destinations, with a direct-edge fallback for final targets |
| `F` | Final sorting | Select a matching final outgoing destination directly; otherwise obtain a transfer/bypass toward it |
| `E` | EBS | Use EBS destinations, a reachable final destination, an intermediate target, or an exit back toward a sorter |

Selected destinations are ordered by `Weight`. The base code returns one, or up to six when `MultipleTargets` is enabled. Default edges are used in several fallback paths. Destination `0` appears in no-sort/waiting paths, but its exact physical behavior depends on the PLC protocol.

## Identification and flight allocation

`CalculateFinalTargets` searches using the item's accumulated valid, distinct barcodes, rather than only the current request's scanner readings.

| Result | Handling |
| --- | --- |
| No valid barcode | `NoRead` |
| One barcode, no matching BSM | `NoBsm` |
| Several unresolved barcodes without a BSM | `MultipleRead` |
| Several unresolved BSMs | `MultipleBsm` |
| One resolved BSM | Associate bag details and continue flight/allocation processing |

Configured policies can resolve some ambiguity: a unique RUSH BSM can survive when other flights are closed, and an optional policy can retain the only open-flight BSM. The code also distinguishes inbound and outbound baggage messages and has local-flight handling.

With a resolved BSM, the normal workflow checks individual baggage sorting instructions, finds the flight, and calls `GetFinalTargets`. In `SortingDB`, this delegates to SQL procedure `Sort_GetFinalTarget` with flight ID, destination, class, onward flight/destination, and exception information. The C# retains rows whose `Prio` equals the first returned row's priority. The procedure's ordering and allocation rules remain unavailable.

Loading authorization, flight merge/redirect rules, and missing flight/allocation conditions can change the resulting targets. Active non-overridable destinations normally suppress normal target recalculation, but do not remove subsequent security and operational processing.

## Timing: early, open, closing, and late

Early and late primarily classify the bag relative to its flight allocation's acceptance window. They are not permanent attributes inferred from conveyor speed or equipment IDs.

| Classification | Visible decision |
| --- | --- |
| Open | At least one allocation returns `isOpen()` |
| Early | No allocation is open and the first allocation returns `isEarly()` |
| Very early | Configured very-early timestamp or offset places the bag sufficiently before opening |
| Closing | An open allocation returns `isClosing()` |
| Late | No allocation is open and the first allocation returns `isLate()` |

Fields include `openTS`, `ClosedTS`, `VeryEarlyTS`, `VeryEarlyOffset`, and closing destinations. `FinalTargetsData` is missing, so the precise implementations, equality boundaries, and relationship to manual allocation statuses cannot yet be stated.

For closing allocations, the code prefers an explicit closing target, then a configured last-minute special target, then the normal target. Late bags can enter CDG relabel/reroute handling before the late special exit fallback.

No explicit generic `Fast` or `FastTrack` category was found in the examined sorting flow. Last-minute routing, closing targets, and RUSH handling are separate mechanisms. A final destination means the intended ultimate sorting exit.

## EBS waiting behavior

For an early bag, the code can select a configured early/very-early special exit before calling `_EBS.ProcessBag`. EBS returns waiting-area targets. Some return values use target `0` with a type indicating a final or very-early outcome; these are interpreted as service signals rather than simply physical waiting destinations.

When EBS targets are recorded, active final/special destination records are disabled. Seeing an item at a DP triggers EBS accumulation removal bookkeeping. Open or late allocation handling can also remove EBS bookings. In an EBS context, normal selection can return the bag toward a sorter when no direct appropriate final destination is available.

Waiting-line selection, occupancy/capacity policy, booking, release triggers, release ordering, and physical release commands are inside missing EBS services/workers. These files show the integration boundary, not the complete EBS lifecycle.

## OCR, MES, and special exits

`HandleSpecialFlow` handles unresolved identification. Its order depends on DP context, OCR/MES availability, pending OCR state, enabled MES reasons, and `SPECIAL_PRIORITY_OVER_MES`.

Outside EBS, handling can prioritize a configured special exit, queue OCR or route toward OCR, route to MES, or fall back to the reason-specific special exit and `NotOk`. EBS has separate behavior, including returning toward a sorter where appropriate.

CDG returns `false` from `ReturnOCR()`. OCR requests can therefore coexist with further physical routing toward MES or another fallback in relevant paths. Negative OCR processing IDs are used as request signals; they should not be interpreted as physical destination IDs.

Special categories such as `Early`, `VeryEarly`, `Late`, `NoAllocation`, `NoATL`, and `NotOk` are explicit in the C# workflow. Their physical targets come from `GetSpecialSort`, which calls a SQL stored procedure using sorter, handler (`HDL`), airline (`FLC`), and special-sort category.

## Security decisions

Security can supersede ordinary sorting where `CheckSecurity` applies. The gate also considers existing clearance, required screening level, security priority relative to MES/OCR, and whether the bag has applicable routing/BSM information.

CDG uses security lookup records containing fields such as `NextSecurityLevel`, recirculation limits, and `CalculateSorting`:

- Intermediate points normally direct unknown security toward X-ray, with level 4 fallback.
- Final sorter points can clear selected/final targets while awaiting level 2 or 3 results, allowing waiting on the sorter.
- Configured timeouts create new timeout security states and cause reevaluation.
- Further-intervention and contamination cases can direct bags toward level 4.
- OSS eligibility can remove the normal screening requirement under airport/airline configuration.
- Clearance can expire or be reset after configured flight changes, RUSH handling, or reintroduction conditions.

Some security routes bypass normal recirculation escalation. Exact numeric security meanings require the missing lookup tables and models. Unknown or waiting security should not be conflated with an ordinary flight-exit capacity problem.

## CDG operating modes and recirculation

| Mode | Visible behavior |
| --- | --- |
| `Nominal` | Common BSM, allocation, timing, and security flow |
| `BSMRush` | Special allocation and missed-flight, RUSH, reroute, and relabel handling |
| `MLP` | OCR/MES-origin BSM handling and airline/destination-based sorting |
| `PreTri` | Intended flight-based pre-sorting allocation; skips normal security-target calculation |
| `TestSecuriteGlobale` | Configured clean/suspect test destinations |
| `DeposeSortie` | Item-specific dépose allocation, with `NotOk` fallback |

Some methods in the supplied `SortingDB` are virtual placeholders, including `SortByDES`, `SpecialSortOnBSM`, and `SortByDepose`. Their actual production behavior requires the concrete airport-specific DAL overrides.

CDG recirculation generally adds alternative chutes after a first threshold, then adds `NotOk` after a further threshold. Without configured alternatives, `NotOk` can be added after the first threshold. Level-4 security targets marked accordingly are exempt. RUSH mode has separate short waiting and relabel/late extraction rules.

The supplied PreTri branch contains an impossible condition checking the same `TransferLink` against both `OCR` and `MES` with `&&`. Its intended allocation branch is unreachable as written. This is a source observation, not confirmation that the same revision runs in production.

## SQL topology and intermediate routing

### Sorting edges

`SortingDB` caches `vwSortingEdges`. `GetSortingEdges` filters that data by `EdgeStartID`, or by `ComponentIndexStart` and PLC node. Without caching, it performs equivalent SQL queries.

This is adjacency-list access to a directed graph stored in SQL. A graph database is not required. Relevant fields include start/end tracking IDs, equipment component IDs, weights, default targets, DP context, area, output rules, and multiple-target strategy.

The observed routing model connects tracking/decision points and equipment destinations. It does not demonstrate that every physical conveyor is represented. The view definition, table contents, and Flow Manager topology are needed before asserting that the production topology contains only DPs or contains every conveyor.

### Four path IDs

`Sorting_GetIntermediateTargets` uses these fields from `FlowManagerPaths`:

| Field | Meaning established by the query |
| --- | --- |
| `TPStart` | Current routing start point |
| `TPSortingDestination` | Next commanded sorting/tracking destination, connected to the start by a sorting edge |
| `TPDestination` | Downstream point whose outgoing edge reaches an eligible final target |
| `IDSnapshot` | Routing-data version |

```text
TPStart → TPSortingDestination … → TPDestination → requested final target
          next commanded exit
```

`TPDestination` is not necessarily the final chute. The SQL joins an outgoing edge from it and matches that edge's `ComponentIndexEnd` against requested final equipment targets. The middle path is represented by the stored path record; its generation is not present in this procedure.

### Runtime selection algorithm

The procedure selects existing records rather than visibly running a recursive graph traversal or shortest-path search:

1. Admit baseline snapshot `0`.
2. Also admit the latest Flow Manager snapshot satisfying the freshness/configuration checks: `DATEDIFF(MINUTE, UpdateTS, GETDATE()) < 5`, action-point `CommandMode` defaulting to and equaling `3`, and `FM_ENABLED` equal to `true` under the query's handling of null values.
3. Find path records beginning at `@TPid` whose downstream final edge has context `E` or `F` and matches a requested final target.
4. Require the next sorting edge to permit discharge, using explicit edge `dischargeStatus` first and equipment status as fallback, with missing values defaulting to permitted.
5. Score candidates with stored path weight plus next-edge and final-edge weights, adding `5` if downstream equipment cannot discharge.
6. Group by snapshot, next target, tracking target, output rule, and sorter preference, retaining each group's minimum score.
7. Select `TOP 1 WITH TIES`, ordered first by snapshot ID descending, then by `SorterPrioOrder * 1000 + floor(minimum weight / 10)`.
8. Return the selected groups ordered by exact weight.

This makes snapshot ID precedence explicit. Within a snapshot, sorter preference has a large numeric bias, not a separate lexicographic sort key; unusually large weight differences could overcome it. Weight bands allow multiple candidates to tie. Stored weights are not proven to be distance or travel time.

Baseline records can be selected when no eligible dynamic candidates survive. The procedure accepts `@IDSnapshot`, but its shown body does not use that parameter to select snapshots. The C# caller passes `0`.

### C# processing after SQL selection

`GetIntermediateTargets` uses the master TP where configured. With `KeepSorter`, it passes the item's last successfully used sorter as a preference. With `ORDERED_INTERMEDIATE`, it calls a different procedure and passes ordered final targets; that procedure is still missing.

Results are sorted by weight. A `BALANCE` output rule invokes `Sorting_BalanceTarget` and moves its chosen target to the front. The later base selection orders by weight again, so the ultimate effect depends on returned weights and stable ties; promotion should not be assumed to override unequal weights.

Area routing calls `Sorting_GetAreaTargets`. Its C# output handling can balance targets, avoid previously used targets, limit the count through `MAX<n>`, or apply equipment-specific EDS preferences. The balancing algorithm remains inside missing SQL.

### Availability and PLC strategy

`FilterNotReachableTargets` checks configured DP/target entries in a SQL object of that name against component status. It removes matching targets whose status has `bCanDischarge = 0`. This method is not a demonstrated general graph-reachability traversal.

`GetTargetsStrategy` takes the minimum configured `MultipleTargetsStrategy` across matching edges, with defaults/error fallback in C#. The actual numeric strategy semantics need PLC protocol documentation.

The currently supported interpretation is:

```text
Path/configuration producer → SQL path records and snapshots
                           → SQL intermediate-target selection
                           → C# handling and destination selection
                           → PLC instruction
```

Which component creates those path records, and how it computes them, is still unknown. Flow Manager naming and snapshot usage indicate an integration, but do not prove its path-generation algorithm.

## Instructions versus confirmed outcomes

`CreateSortingInstruction` prepares PLC-facing destination entries, strategy, baggage identification, and security information. The request-processing worker and transport are missing.

`SetResult` and tracking handlers process actual equipment outcomes. They distinguish final sorting from intermediate movement, record tracking, complete individual sorting instructions, and create baggage processing messages. A commanded destination is not proof of physical arrival or successful discharge.

## Comparison with Flunav

Both systems separate intended destinations from selected physical routing and revisit routing decisions. The main difference is the responsibility split and airport-specific behavior, rather than C# versus Java.

| Responsibility | Production sources | Flunav code examined |
| --- | --- | --- |
| Business destination | BSM and flight allocation | Explicit item destinations or property/root-field mapping rules |
| Physical exits | SQL allocation/special/area records | Logical destination-to-exit mappings or direct location IDs |
| Route selection | Stored path records selected by SQL | Java search across available conveyor connections |
| Preference | Stored weights, sorter bias, output rules, equipment strategy | Travel time, utilization, priority, and capacity reservations |
| Timing | Explicit early/open/closing/late workflows | Mapping validity windows and `rushAt` priority rules |
| Security | Airport screening state and policy | No equivalent airport security workflow in the examined routing engine |
| Waiting | EBS service integration | Capacity waiting, staging conveyors, and timed nodes |
| Recirculation | Threshold-based alternatives and special exits | Main-path fallback, reevaluation, and analytics |
| Equipment output | PLC destination alternatives and strategy | RabbitMQ destination/path assignments |
| History/simulation | Not established by these sources | Event reduction, stored history, snapshots, virtual time, and isolated contexts |

Flunav's [RoutingDecisionService](../backend/src/main/java/com/flunav/backend/services/RoutingDecisionService.java) searches usable conveyors and selects an exit/path. Cost includes conveyor length/speed and timed-node processing delay. Priority favors fast routes; normal priority favors less utilized exits. Capacity selection includes occupancy, assigned items, and pending reservations.

[DestinationMappingService](../backend/src/main/java/com/flunav/backend/services/DestinationMappingService.java) evaluates item fields/properties within `validFrom`/`validTo`. A matching `rushAt` rule can raise effective routing priority to `1.0`. This RUSH priority is not equivalent to CDG relabeling and baggage-exception workflows.

[ItemMovementProcessor](../backend/src/main/java/com/flunav/backend/services/ItemMovementProcessor.java) reevaluates physical routing at DPs using stored item destinations. That does not automatically repeat the C# BSM/flight-allocation workflow. Skipping an out-of-window destination mapping also does not itself classify a bag as early/late or arrange EBS release.

## Integration and taking sorting decisions

### Digital twin alongside the production sorter

A mapper could translate production messages into Flunav events while the existing sorter owns decisions. It would need stable identities, timestamps, ordering, duplicate handling, topology/sensor mapping, and a distinction between requested and confirmed actions.

| Production fact | Flunav integration concept |
| --- | --- |
| Bag appears | Item creation |
| Scanner/TP detection | Position update |
| Routing assignment | Destination/path update |
| Confirmed discharge | Item exit |
| Equipment state | Conveyor availability/speed or other mapped operational state |
| Flight/barcode/security data | Item metadata/properties as appropriate |

These are semantic correspondences, not a finished protocol specification. External-routing mode and event effects must be validated so Flunav does not independently choose a conflicting route.

With only coarse DP topology, Flunav can represent confirmed movements between mapped points. Accurate continuous movement needs physical connections, lengths, speeds, and sensor positions, or explicitly documented approximations.

### Flunav choosing routes

Flunav already selects exits and paths and publishes live assignments through [PathAssignmentPublisher](../backend/src/main/java/com/flunav/backend/services/PathAssignmentPublisher.java). An equipment adapter would need to convert assignments into the required PLC protocol.

A feasible responsibility split is:

```text
Airport services supply eligible destinations and constraints
    → Flunav selects an operational route
    → adapter issues equipment commands
    → tracking/result events confirm the outcome
```

The eligible-destination/constraint contract is proposed, not a complete existing integration. Security, EBS, flight windows, commitment boundaries, and PLC strategies must be represented before controlling the real system. Replacing all C# airport policy requires substantially more than an event mapper.

Validation would need command timing, equipment strategy compatibility, routing ownership, capacity semantics, tracking reconciliation, communication failures, and production-load behavior. Observation and replay provide a concrete first integration stage; routing control can follow once behavior is verified.

## Missing sources that would resolve remaining questions

1. Definitions of `vwSortingEdges`, `vwSortingEdgesLite`, and underlying edge tables, plus representative rows: topology granularity and field semantics.
2. The producer/population logic for `FlowManagerPaths` and `FlowManagerPathSnapshots`: actual path-generation algorithm and snapshot lifecycle.
3. `Sorting_GetOrderedIntermediateTargets`, `Sorting_BalanceTarget`, and `Sorting_GetAreaTargets`: ordered routing, balancing criteria, and area reachability.
4. `Sort_GetFinalTarget` and `GetSpecialSort`: allocation precedence and configuration matching.
5. `SortingEdgeData`, `TargetSortData`, and `FinalTargetsData`: field meanings, weights, and timing predicates.
6. Concrete CDG DAL overrides: special-mode allocations, BSM relation/relabel handling, and departure status.
7. EBS service and release workers: booking, line selection, capacity, release ordering, and commands.
8. Security lookup tables and tracking DAL: security codes, transitions, reconciliation, and result persistence.
9. Destination-request worker and PLC protocol: transport, deadlines, destination `0`, multiple-target strategy, OCR completion, and failure handling.

No code ownership or event entry point was moved by this documentation work, so `CODE_MAP.md` does not need an ownership update.
