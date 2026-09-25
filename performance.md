
  In a representative 2,399-item run, the main cost was repeated Redis scans, not pathfinding or the position write:

   Measured work                                      Time
  ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━  ━━━━━━━━
   Loading all active item hashes, 11,996 times     12.7 s
  ───────────────────────────────────────────────  ────────
   Item creation events, including network entry    25.6 s
  ───────────────────────────────────────────────  ────────
   Position change events                            4.2 s
  ───────────────────────────────────────────────  ────────
   Actual path search                                11 ms

  The timings overlap, so they should not be added together. Path results are already cached in Redis. Capacity checks repeatedly reload active items to count chute assignments; that is the next place to change if we want a
  substantial speedup. Reaching five minutes for 500 runs would require much more than the current caching changes.

  The representative benchmark and a focused two-run integration test passed. The running backend needs a restart to emit the new logs.

  Multi-simulation runs now keep their temporary item state, occupancy queues, path caches, routing mappings,
  anomaly state, and lifecycle metadata in a per-run in-memory store. Capacity checks maintain assigned-exit and
  waiting-for-capacity indexes, so they no longer reload every active item. Redis remains the backend for live,
  replay, and interactive what-if contexts; a multi-run store is discarded when its run completes or fails.
