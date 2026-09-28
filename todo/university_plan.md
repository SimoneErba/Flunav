# University simulation implementation plan

## Goal and priorities

Make Flumen usable by professors, students, and researchers who build conveyor models on individual installations, exchange models, and compare alternatives through reproducible experiments.

The primary workflow is: **choose a template → edit an isolated scenario → export or import it → create alternatives → run repeated experiments → compare and export results**.

Implement in this order:

1. Portable simulation scenarios and isolated import/export.
2. Conveyor component presets and complete layout templates.
3. Controlled comparison of simulation alternatives.
4. Research reporting, validation, and university onboarding.

The first release targets individual projects. Shared university tenancy, LMS integration, custom control algorithms, new physical models, and exact runtime checkpoint/resume are separate work.

## Existing foundation and constraints

- Graph editing, replay, What If branches, virtual time, capacity/spacing behavior, and isolated simulation storage already exist.
- `MultiSimulationService` captures a graph/configuration baseline; ClickHouse persists experiment definitions, per-run seeds, metrics, and reports. Extend these facilities rather than build a second experiment engine.
- Current `.flugraph` files contain graph state and sensor mappings. They do not contain a complete experiment definition or all routing/display configuration.
- Current What If branches can receive live item inputs. A portable university scenario must have an explicitly detached lifecycle.
- Multi-simulation inputs support one source, fixed or Poisson arrivals, destination probabilities, rate variation, and exponential failure intervals with fixed repair durations. Retain these capabilities in the first release.
- The current input generator uses one random stream for several purposes. Controlled comparisons need independently controlled streams.
- Existing tests cover movement, isolation, replay, and same-seed metrics. They are a foundation for verification, not evidence of physical model accuracy in every conveyor system.

Preserve the event model and reducer. Domain edits use existing events through `EventProcessor`; snapshot restoration may use the established derived-state restore path. Never append imported, template, or comparison events to live ClickHouse history. Keep simulation and virtual-time contexts paired across asynchronous boundaries.

Update `CODE_MAP.md` when new services take ownership of scenario storage, import, templates, or comparisons. Consult `AGENTS.md`, `GEMINI.md`, and `TODO.md` during implementation; outstanding TODO items do not expand this scope.

## 1. Portable simulation scenarios

### File format and saved state

Introduce `.flusim`, a UTF-8 JSON document with a versioned envelope. Use JSON for the first release; attachments and archive containers are unnecessary for these models.

The version 1 document contains:

| Section | Contents |
| --- | --- |
| Manifest | Format identifier, schema version, scenario name/description, export time, Flumen release/commit when available, model semantics version, input-generator version, and units. |
| Baseline | Graph topology, sensor mappings, optional initial items, and an explicit virtual start timestamp. |
| Configuration | Routing/destination configuration and display rules needed to reproduce behavior and presentation. Use explicitly supported typed payloads; export effective state rather than an arbitrary event log. |
| Experiment | Optional existing multi-simulation input configuration, duration, replication count, and resolved base seed. |
| Provenance | Source identifiers as informational metadata, canonical topology/configuration hashes, and template identity/version where applicable. |
| Results | Optional archived per-run results and aggregate report, with their original provenance and measurement definitions. |

Use SI units for physical parameters, UTC timestamps, and explicit conversion for existing item-length properties expressed in centimeters. Graph coordinates remain visual coordinates rather than conveyor lengths.

Provide two export actions:

- **Export scenario:** capture the model and effective configuration. Default to an empty item population; allow initial items through an explicit option. For a paused simulation, capture positions at its virtual clock. For live state, use the existing consistent snapshot barrier.
- **Export experiment:** export the original immutable baseline, inputs, seeds, and optional archived results. Do not substitute the experiment's terminal graph for its starting baseline.

Version 1 supports a fresh run from the exported baseline. It does not promise exact continuation of a previous execution: in-memory queues, worker handles, and live subscriptions are not serialized. Keep this distinction visible in the import/export UI.

Preserve `.flugraph` compatibility through a legacy reader. Import it as a graph-only scenario, show that routing/display configuration and experiment inputs are absent, and require the user to complete missing settings before running. Keep the existing graph endpoints compatible.

### Validation and import

Add a dedicated scenario validation/import service. Validate before creating storage or applying events:

- Supported format/schema/model versions, required fields, finite numeric values, timestamps, unique entity identifiers, and valid graph references.
- Conveyor lengths, speeds, capacities, gaps, item positions, sources, destinations, sensor references, and configuration references using existing domain rules.
- Arrival/failure settings, probability totals, replication limits, and JSON-safe seed values using shared experiment validation.
- File size and configured entity/event limits. Set the initial upload limit to 20 MiB and honor existing simulation capacity checks; make the upload limit configurable.

Reject unsupported newer schema/model versions with an actionable message. Older supported formats use explicit adapters. Allow unknown informational metadata, but reject unknown behavioral configuration types. Never accept arbitrary Jackson class names from files.

Import creates a new detached simulation with fresh runtime identifiers. Preserve model entity identifiers inside its namespace so routing references and comparison mappings stay stable. Source runtime identifiers never become local ownership identifiers.

Add `SCENARIO` as an additive simulation kind and a corresponding creation path. It starts paused, uses its own OrientDB/Redis context, and neither subscribes to live ingestion nor reads external ClickHouse history during playback. Adapt playback and snapshot refresh paths explicitly for this kind. Reconstruct internal movement schedules from supported initial state; reject initial states that cannot be reconstructed safely rather than silently dropping pending behavior.

On failure, clean up the entire newly allocated simulation namespace and database. Archived results remain historical records associated with the imported scenario; they never make a new execution appear completed. Mark results from another model/generator version as such.

### Scenario library and interfaces

Provide a named local scenario library with save, open, duplicate, rename, and export. Store durable scenario definitions and immutable revisions in OrientDB through a repository; keep runtime state in namespaced Redis and experiment results in ClickHouse. Store the portable document as the revision payload with searchable metadata. An experiment captures a revision and remains unaffected by later edits.

Suggested interfaces:

- `GET/POST /api/scenarios` and `GET/PATCH /api/scenarios/{id}` for listing, saving, and metadata updates.
- `POST /api/scenarios/import/validate` for file preview and validation diagnostics.
- `POST /api/scenarios/import` to validate again and save a new scenario revision.
- `POST /api/scenarios/{id}/open` to create an isolated paused runtime from a chosen revision.
- `GET /api/scenarios/{id}/export?revision=...` and `GET /api/multi-simulations/{id}/export?includeResults=...` for downloads.

Preview validation creates no scenario or runtime. Return 400 for malformed/invalid data, 413 for oversized uploads, 404 for missing records, and 409 for incompatible state or unavailable runtime capacity.

Keep read access authenticated and mutations restricted to the existing ADMIN/SUPERADMIN roles, matching experiment creation. Do not introduce university roles or multi-user ownership in this release. Regenerate OpenAPI client types for new contracts; do not hand-edit generated files.

### Acceptance

- Export a scenario on installation A, import it on installation B, and rerun it without A's history or current live configuration.
- A round trip preserves model/configuration hashes, experiment parameters, resolved seeds, and initial state within documented numeric precision.
- Opening/editing/running an imported scenario leaves live graph state, live Redis keys, live event history, and other simulations unchanged.
- Invalid imports allocate no lasting runtime state. Saved definitions survive restart and reopen into fresh runtimes.

## 2. Conveyor presets and layout templates

Support both component presets and complete teaching layouts. Both use the scenario validation and serialization infrastructure.

### Component presets

Add a preset picker to conveyor creation and editing. Initial built-ins are belt, accumulating roller, chute, and staging conveyor. Presets configure supported fields: type, length, speed, minimum distance, capacity, main-path status, and supported type-specific properties.

Expose spacing and capacity as typed, unit-labeled fields rather than requiring generic property editing. A preset copies its values into the conveyor; editing an instance never modifies the preset. Existing items and topology are unaffected until the user submits the normal event-based edit.

For initial built-ins, use the supported values already exercised by the repository's corresponding movement/spacing/staging cases. Publish exact values in each preset definition and explanation. Label them illustrative defaults rather than validated manufacturer specifications.

Let users save the selected conveyor's supported settings as a named custom preset. Store custom presets in OrientDB, alongside durable configuration. Presets exclude entity ids, endpoint ids, alarms, occupancy, and runtime stop state. No new conveyor physics is introduced by selecting a preset.

### Layout templates

Ship four versioned layouts:

1. **Simple line:** one source, conveyors in series, and one exit; demonstrates travel time and throughput.
2. **Merge and bottleneck:** two feeder paths joining a slower shared conveyor; demonstrates spacing, queues, and capacity constraints. Use one arrival source feeding a split/rejoin topology in the runnable v1 experiment; explain the one-source limitation.
3. **Sorting loop:** a recirculating loop with destination exits; demonstrates routing and full-exit behavior.
4. **Failure and recovery:** a main route and alternate path with a configured failure/repair experiment; demonstrates resilience.

Each template includes a preview, parameter values and units, learning objective, model assumptions, supported input defaults, and documented expected qualitative behavior. Every template must be runnable through the existing single-source input model.

Store built-ins as version-controlled backend resources. Instantiate a template as a new saved scenario and detached runtime; never clear or replace the live graph. Copy its configuration so later template updates do not alter existing projects. Custom layout templates are saved scenario revisions marked for reuse, with an empty item population by default.

Expose `GET /api/simulation-templates`, `GET /api/simulation-templates/{id}`, and `POST /api/simulation-templates/{id}/instantiate`. Version component presets separately and expose equivalent list/save endpoints under `/api/conveyor-presets`.

### Acceptance

- Each built-in opens on a fresh installation and runs without the Java simulator or manually populated live graph.
- Repeated instantiation produces independent projects. Editing a copy does not mutate the source template or another instance.
- Preset application passes the same domain validation as manual editing and retains event replayability.

## 3. Experiment comparison

### Variant workflow

Add a comparison workspace where the user chooses one saved scenario revision as a reference, duplicates it into named alternatives, edits alternatives, and selects shared duration, replication count, and base seed.

Version 1 supports explicit alternatives and parameter sweeps over conveyor speed, capacity, or minimum distance. Require explicit value lists and expand one swept parameter per study; factorial experiment design is deferred. Reject expansions beyond configured experiment/run limits before saving or scheduling work.

Refactor experiment creation into two entry paths: capture the current context, preserving today's endpoint, or create from an explicitly supplied validated scenario revision. Both use the same immutable baseline builder and runner. Saved-baseline creation must not read the current live topology, routing configuration, or wall-clock start time.

Comparison alternatives become ordinary persisted multi-simulations. Store a comparison definition and its ordered alternative experiment ids in a new ClickHouse table, following existing versioned experiment-storage conventions. Freeze definitions when execution starts; edits create a new comparison.

All alternatives in a controlled study use the same virtual start time, duration, initial-population policy, arrival model, destination probabilities, replication count, and base seed. Validate that destinations are reachable in each alternative. Matching entities retain ids; explicit layout alternatives must provide a source/destination mapping to the reference. Do not infer equivalence from display names.

### Randomness and execution

Introduce an input-generator version. Keep existing persisted/file experiments without that field on the legacy generator; new controlled comparisons use the new version.

Derive deterministic random streams from the base seed, run index, and purpose: arrival-rate variation, arrival intervals, destination selection, and per-conveyor failures. Use stable conveyor ids and a documented hash/seed derivation so iteration order and unrelated conveyor changes do not change existing streams.

Pair alternatives by run index. Preserve common arrivals and destination draws when only topology parameters change. Share failure draws for matching conveyors where the configured failure model is unchanged. Document that changing a failure distribution does not guarantee identical outage times.

Use the existing bounded multi-simulation worker pool. Comparison orchestration must not create an independent unbounded pool. Track queued/running/terminal alternatives, expose partial failures, and support cancellation. Completed results survive cancellation. After restart, mark interrupted execution honestly and allow a fresh rerun from the frozen definition; exact continuation is deferred.

### Results and export

Show alternatives side by side with units, replication counts, completion counts, throughput, mean/p95 journey time, items remaining, maximum population, recirculation, and conveyor downtime. Include per-run tables and simple comparison charts using the existing chart library.

For each scalar metric, report the difference from the reference. Compute a 95% paired Student-t confidence interval from differences for successfully completed matching run indexes. Report the pair count and excluded runs; require at least two pairs. Zero-variance differences produce a degenerate interval, and a zero reference mean produces no percentage change.

Label P5–P95 as a run distribution range. Do not describe it as a confidence interval. Independently selected older experiments may be displayed descriptively, but do not claim paired inference without matching controlled inputs and generator provenance.

Preserve metric definitions and show limitations: journey times describe completed items; items remaining expose incomplete journeys; no observed completions should display as unavailable journey statistics rather than measured zero. Describe recirculation as passes per 100 completions where that is the current denominator, since it can exceed 100. Apply availability handling additively so legacy stored results remain readable.

Exports include a comparison JSON bundle containing every alternative baseline/configuration, provenance, seeds, and results, plus a CSV with one row per alternative/run and a summary CSV. Escape CSV text and guard spreadsheet formula prefixes in user-supplied labels. Add a visible download action for these files.

Suggested interfaces: `POST/GET /api/simulation-comparisons`, `GET /api/simulation-comparisons/{id}`, and `POST .../{id}/run`, `POST .../{id}/cancel`, `GET .../{id}/report`, and `GET .../{id}/export?format=json|csv`. Use the same authorization and lifecycle response patterns as multi-simulations.

### Acceptance

- Identical alternatives produce identical metrics and zero paired differences under the same version and seeds.
- Changing conveyor speed preserves the paired arrival/destination inputs. Reordering conveyors does not perturb their failure streams.
- Imported comparisons rerun from their archived definitions, independent of the receiving installation's live state.
- Failed/cancelled runs are visible and excluded transparently. Completion times alone cannot hide unfinished populations.

## 4. Verification and delivery

Implement as reviewable milestones:

| Milestone | Deliverable | Completion gate |
| --- | --- | --- |
| A | Versioned scenario DTOs, validation, durable revisions, detached lifecycle, import/export UI. | Cross-install round trip, legacy import, restart/reopen, and live-state isolation verified. |
| B | Component presets, four layout templates, and template gallery. | Every template instantiates independently and completes its documented experiment. |
| C | Frozen comparisons, controlled random streams, variant/sweep UI, reports, and exports. | Same-input pairing, deterministic results, bounded execution, cancellation, and report calculations verified. |
| D | Research reference cases, short teaching guide, and release verification. | A professor completes the end-to-end exercise on a fresh installation using only the guide. |

Use Testcontainers-backed Spring integration tests with real OrientDB, Redis, ClickHouse, and RabbitMQ where the tested path requires them. Do not add mocks, fake services, or infrastructure substitutes.

Cover malformed/future-version files, reference validation, size limits, configuration isolation, cleanup after restore failure, partial runs, seed compatibility, simultaneous simulations, and authorized/rejected access. Compare canonical model content rather than byte-for-byte exports containing fresh timestamps and ids.

Add Playwright coverage for import preview, template selection, editing/saving/reopening, scenario export, comparison creation, progress/cancellation, result availability, and downloads. Preserve dark mode and responsive behavior.

Because detached scenarios and versioned generators affect shared simulation paths, verify existing replay, What If, playback, movement, same-seed, and isolation integration classes as well as new focused tests. Run frontend lint/build and relevant browser tests. Build/install commons first only if shared event contracts change, then verify affected consumers.

Define reference cases with expected travel times, spacing/capacity constraints, merge behavior, full-exit recirculation, and stop/restart outcomes. Document tolerance and event tie ordering. Physical conveyor accuracy claims require a separate measured-data validation exercise.

Apply ClickHouse additions to both runtime initialization and Testcontainers schemas, including an idempotent forward-upgrade script for existing volumes. Exercise upgrades twice and confirm existing experiment rows remain readable. Initialize new OrientDB classes idempotently through the established setup path.

Add a pull-request verification workflow for focused integration and frontend checks; the current release publishing workflow skips backend tests. Publish the verified application version used by exported provenance, and document supported model/generator versions.

Write one university exercise: instantiate a line, change a conveyor speed, run both alternatives with shared inputs, inspect unfinished items and uncertainty, export the study, and reproduce it on a second installation. Include setup requirements and supported simulation limits. Review distribution licensing before publishing the university release.

## Deferred work

- Exact save/resume of event queues and long-running executions.
- Multiple independent arrival sources, recorded arrival traces, item-class distributions, and stochastic repair durations.
- Warm-up/measurement-window configuration, drain periods, precision-based stopping, factorial designs, and optimization.
- External algorithm APIs, reinforcement learning, hardware-in-the-loop, and additional physical fidelity.
- Shared university projects, project-level roles/quotas, course assignments, and LMS integration.

These extensions must preserve the same portable scenario and frozen-experiment model. The first release should clearly state its fixed-horizon measurement rules and supported physics rather than imply broader research coverage.
