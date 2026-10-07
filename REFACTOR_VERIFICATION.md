# Refactor verification

Completed on 2026-09-28. [CODE_MAP.md](CODE_MAP.md) records the resulting entry points and state owners.

## Implementation

EventProcessor retains ordering, context entry, retries, barriers, and live history persistence while delegating domain effects to four reducers. Rule fields and Redis item position projection are shared components. SimulationService delegates lifecycle, playback, capacity, runtime metadata, and internal event projection. ClickHouseService retains its public methods and nested result types, shared client, scheduled flush entry points, and shutdown drain order while storage components own their queues and queries.

The graph has one runtime item/index owner, separate item/topology/alarm handlers, separate pointer/editor hooks, and a tagged selection. A stable clock reader drives animation; only playback clock text uses a short React timer. Snapshot refresh triggers and stale response protection belong to useGraph. Workspace transitions share actions, and live/admin routes load separate chunks.

Public contracts, replay timestamp tie ordering, simulation namespaces, RabbitMQ acknowledgement boundary, and existing in-process ClickHouse queue durability remain unchanged. Browser verification exposed the existing playback speed PATCH endpoint being blocked by CORS; PATCH is now allowed for the already configured origins, with integration coverage for allowed origin, rejected origin, and authentication.

## Backend

- Final focused Testcontainers run: **21 tests passed**, zero failures/errors. Covers rule parity, snapshot restore, alarm effects, simulation isolation, What If forks and capacity, build queue handling, playback pause/speed, RabbitMQ delivery, CORS, and stress ordering/isolation.
- Full backend run after reducer/storage extraction: **214 tests, 10 failures, 5 errors**. Analytics, multi-simulation storage, and event delivery tests ran in this suite.
- A failed-test subset on the archived pre-extraction commit `ab118ce`: **17 tests, 9 failures, zero errors**. The same nine functional failures reproduce on the changed code: nanosecond/millisecond timestamp expectations (four), snapshot optional defaults, recirculation routing, timed-node routing, sensor alias precedence, and checkpoint entry timestamp. The checkpoint test had encountered heap admission rejection in the full run before reaching its baseline assertion.
- Full-suite anomaly timing, stress time budgets, heap admission rejections, and simulation timeouts depend on suite order/load. Anomaly and both stress tests pass in focused runs. The full suite remains an unsuccessful gate; these results do not establish a clean full-suite regression baseline.

Local logs: `/tmp/flunav-final-focused-tests.log`, `/tmp/flunav-full-backend-tests.log`, and `/tmp/flunav-baseline-backend-tests.log`.

## Frontend

- Lint passes with one existing unused eslint directive warning in the generated client.
- Vite build and Playwright TypeScript checking pass. Build chunks include AdminWorkspace **39.10 kB**, LiveWorkspace **121.45 kB**, and the root bundle **681.59 kB** (existing size warning).
- Application TypeScript checking still reports **23 diagnostics** in untouched files. All match diagnostics from the archived `ab118ce` baseline, which reports 55; there are no new diagnostic messages. This check remains unsuccessful.
- **13 distinct Chromium checks passed** across two production test-build runs: reconnect reconciliation, live WebSocket-created edges/items, workspace URL/navigation transitions, clock lag visibility (two cases), speed updates, stop condition, conveyor activation, chute empty, staged FIFO release, simulation pause/speed/What If isolation, and the many-item trace. The first run's clock assertion differed by one floating-point ULP; after using a position tolerance, the corrected test passed with a real successful speed PATCH response.

Local logs: `/tmp/flunav-final-browser-tests.log`, `/tmp/flunav-final-browser-recheck.log`, `/tmp/flunav-frontend-build.log`, `/tmp/flunav-tsc-current.out`, and `/tmp/flunav-baseline-tsc.out`.

## Animation comparison

Same production test harness, 250 real conveyor items, three seconds of steady live movement, with Chrome timeline/V8 tracing. The pre-animation source is `d1da681`; only test render instrumentation and the current harness were added to that archived source.

| Measurement | Before | After |
| --- | ---: | ---: |
| Workspace React commits | 49 | 0 |
| Sampled animation frames | 51 | 48 |
| Median frame interval | 66.6 ms | 50.1 ms |
| 95th percentile interval | 100.0 ms | 116.7 ms |
| Item movement assertion | Passed | Passed |

These single headless samples demonstrate removal of frame-driven workspace renders with continued graph movement. They do not establish a consistent frame-rate or tail-latency improvement. The first sample interval is excluded from interval percentiles because requestAnimationFrame timestamps can precede the sampling call.

Standalone summary and Chrome trace JSON files are saved locally under the ignored directory `backend/target/benchmark-results/agent-refactor/`, with `before-many-item-*` and `after-many-item-*` names.

To repeat the current measurement from `frontend`:

```sh
E2E_TEST_BUILD=true E2E_REUSE_SERVERS=true pnpm exec playwright test e2e/graph-runtime.e2e.ts --project=chromium --grep 'many-item'
```

The test writes both JSON artifacts to its Playwright output directory. It requires the project browser/integration infrastructure; the test build enables the workspace commit counter.
