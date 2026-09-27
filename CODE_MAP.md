# Change map

Update this map when a state owner or event entry point moves. See [AGENTS.md](AGENTS.md) for the rules that govern live, replay, and simulation work.

| Path | Entry point | State owner and tests |
| --- | --- | --- |
| External live events | `ItemEventListener` → `EventProcessor.process` | `EventProcessor` orders events per entity, enters the captured context, retries conflicts, applies the state barrier, and appends live events through `ClickHouseService`. `RabbitEventDeliveryIntegrationTests`, `ItemPathUpdateIntegrationTests`, `AlarmIntegrationTests` |
| Replay and restore | `HistoricalGraphBuilder` → `EventProcessor.processEventWithoutBroadcast` | `HistoricalGraphBuilder` merges stored external and projected internal events by timestamp. `SimulationPlaybackIntegrationTests`, `WhatIfSimulationIntegrationTests` |
| Simulation playback | `SimulationService` → `EventProcessor` | `SimulationService` owns lifecycle and scheduling; `SimulationCapacityManager` admits builds; `SimulationRuntimeState` owns the process-local cache, playback handles, build permits, and waiting queue; `DatabaseContextHolder` and `TimeService` own scoped storage and virtual time. `SimulationTests`, `SimulationPlaybackIntegrationTests` |
| Rule fields | `RuleFieldProjection` | Shared top-level field names for event updates and graph snapshots. `GraphServiceItemTests`, `StagingEventContractTests` |
| Graph snapshot | `GraphService.getGraphData` | `GraphService` assembles topology and styles the graph; `ItemPositionProjection` derives Redis item positions at the requested domain time. `GraphServiceItemTests`, `ConveyorSpacingIntegrationTests` |
| Browser graph | `GraphEvents` | `useGraphRuntime` owns the active item map and conveyor ID lookup; `useGraphLoader` seeds both from snapshots, and `useGraphLiveEvents` updates both from WebSocket events. `frontend/e2e/graph.e2e.ts`, `frontend/e2e/item-visibility.e2e.ts` |
| Browser clock | `useSimulationClock` | A stable `now()` reader owns virtual display time; `useGraphAnimation` samples it per frame, while `PlaybackControls` updates its text timer. `frontend/e2e/simulation-replay-websocket.e2e.ts` |
| Browser workspace mode | `useWorkspaceModeTransitions` | Navigation and URL mode requests share actions for live, replay, What If, and design. `frontend/e2e/what-if.e2e.ts` |
| Browser snapshot refresh | `useGraph` | Loads after mode changes and READY status, on reconnect, and after `scenario-mutated`; rejects stale responses. `frontend/e2e/graph.e2e.ts`, `frontend/e2e/what-if.e2e.ts` |

Live history is append-only in ClickHouse. Replay reads that history without appending it again. Simulations use their own OrientDB database and `sim:<id>:` Redis keys; worker threads must re-enter simulation and virtual-time contexts. Stored events win timestamp ties against projected internal events. WebSocket broadcasts follow successful state changes, and RabbitMQ acknowledges only after processing completes.

`ClickHouseService` currently buffers writes in process memory and flushes queues on schedules. Queue durability beyond the current process and RabbitMQ delivery changes are separate concerns; do not change their semantics as part of an extraction.
