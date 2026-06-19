# Copy-On-Write Simulation Redesign

## Goal

The current simulation model creates one isolated in-memory OrientDB database per simulation and restores the graph into it. That gives strong isolation, but memory grows roughly with:

```text
simulation_count * (topology + durable item metadata + hot item state)
```

Copy-on-write would reduce this by sharing an immutable baseline graph across simulations and storing only per-simulation changes.

## Model

- Baseline state: an immutable snapshot of locations, conveyors, item metadata, display rules, and initial hot item positions.
- Simulation delta: only records changed by that simulation, plus deleted-entity tombstones, internal event queue, virtual clock, and simulation metadata.
- Read projection: graph reads merge `baseline + simulation delta`, with delta records winning over baseline records and tombstones hiding deleted baseline records.
- Write behavior: the first time a simulation changes a baseline entity, copy that entity into the simulation delta and mutate only the delta copy.

This changes memory growth toward:

```text
baseline + simulation_count * changed_state
```

instead of cloning the full baseline for every simulation.

## Why It Is Deferred

This is a larger architecture change than admission control or paged replay. Every context-aware read must merge baseline and overrides consistently, and every delete needs tombstone semantics. Event replay must also remain deterministic across live, historical, and future simulation modes.

The safer near-term path is to keep the current isolation model and add:

- hard simulation capacity limits
- bounded ClickHouse replay paging
- deterministic cleanup of Redis and OrientDB simulation state
- container and JVM memory caps
- benchmark data for per-item and per-simulation memory cost

Copy-on-write becomes worth implementing when many concurrent simulations start from the same or similar historical baselines.

## Implementation Sketch

- Introduce a `SimulationBaseline` table or service keyed by snapshot id and timestamp.
- Store simulation overrides in a delta namespace rather than a full cloned OrientDB database.
- Add repository methods that resolve entities through `delta -> baseline`, with tombstone checks.
- Keep Redis hot state namespaced per simulation for active item positions, scheduled movement, occupancy, and routing state.
- Add a compact changed-entity index per simulation so cleanup and graph reads do not scan broad prefixes.
- Add tests that prove baseline reads, overridden reads, deletes, replay, and future internal events produce the same visible graph as the current full-clone model.
