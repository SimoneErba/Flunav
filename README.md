<p align="center">
  <img src="logo.svg" alt="Flunav Logo" width="350"/>
</p>
<p align="center">
  A real-time digital twin engine for tracking and simulating complex logistical systems.
</p>

---

> ⚠️ **Work in Progress**
>
> This project is under active development. The backend architecture and core functionalities are largely complete, while the frontend UI is undergoing further refinement.

## What is Flunav?

Flunav is a backend platform and visualization tool designed to create a **digital twin** of systems with moving assets, such as conveyor-based sorting facilities or production lines. It ingests event data from external control systems (e.g., PLCs), maintains a real-time graph model of the system's state, and provides tools for historical analysis and simulation.

The core purpose is to transform discrete sensor data into a continuous, understandable, and actionable view of the entire operation.

<p align="center">
  <!-- A GIF or screenshot of the UI would be effective here -->
  <img src="https://i.imgur.com/your-demo-gif.gif" alt="Flunav in action"/>
</p>

## Core Concepts

- **Dynamic Graph Model:** The entire physical layout of the system is represented as a graph. Users can visually design and modify this layout directly from the frontend.
- **Nodes as Tracks:** Unlike traditional graphs, nodes themselves can represent physical tracks (like a conveyor belt) with properties like `length` and `speed`. This enables realistic, time-based animations of items moving *across* a node.
- **Live System Status:** The visual appearance of every component in the graph dynamically changes to reflect its real-world state, such as `OPERATIONAL`, `STOPPED`, `FULL`, or `ERROR`.

## Key Features

- **Real-time Visualization & Control:**
    - **Live Asset Tracking:** See items move across the facility in real-time.
    - **Dynamic Status Updates:** Instantly identify system-wide issues by watching conveyors stop, items queue up at bottlenecks, or chutes become full.
    - **Interactive Graph Editor:** Design, build, and modify your entire facility layout directly in the user interface.

- **Predictive Path Simulation:**
    - When an item receives a destination, the system calculates the most likely physical path.
    - The frontend animates the item along this predicted route, providing a forward-looking view of the operation.
    - **State Correction ("Teleport"):** If a sensor reports an item in a location that deviates from its predicted path, the UI immediately corrects its position, instantly highlighting operational anomalies.

- **Historical Analysis & "Time Travel":**
    - **High-Fidelity Playback:** Replay past operational periods to analyze specific incidents with frame-by-frame accuracy. See exactly when a conveyor stopped and how that caused a cascading failure upstream.
    - **Complete Item History:** Select any item, past or present, and instantly see its entire event history: every location it visited, every decision point it passed through, and the full timeline of its journey.
    - **"What-If" Analysis:** Create a fork of a historical state in an isolated environment to test the impact of new business rules using historical event data, allowing for data-driven process optimization.

---

## Architecture

Flunav's architecture is designed for resilience, scalability, and data fidelity by separating concerns and using specialized data stores.

### 1. Event Sourcing
The system's source of truth is not the current state, but an immutable log of all domain events (`ItemCreated`, `PositionChanged`, etc.). This provides a complete audit trail and enables all historical features.

### 2. Polyglot Persistence
We use different databases for their specialized strengths:
- **OrientDB (Graph Database):** Stores the *current state* of the digital twin. Its graph structure is optimized for pathfinding algorithms and querying complex relationships between system components.
- **ClickHouse (Columnar Database):** The **event store**. Built for extremely high-throughput ingestion of time-series data and fast analytical queries required for state reconstruction.

### 3. Asynchronous & Decoupled Ingestion
- **RabbitMQ (Message Queue):** The API layer is decoupled from the state processing logic. Incoming events are published to a queue, allowing the API to remain fast and responsive under high load and ensuring data durability. The core system processes events from this queue.

### 4. Isolated In-Memory Simulations
- **On-Demand Environments:** Historical simulations do not run against the live production database. When a user requests a historical view, a new, dedicated **in-memory OrientDB database** is created instantly.
- **"Golden Template" Pattern:** To avoid slow schema creation, new in-memory databases are cloned in milliseconds from a pre-configured, schema-ready in-memory template that is created once at application startup. This ensures that analytical workloads are fully isolated from the live operational system.

## How Simulations Work

Simulations are isolated forks of the live event model. A simulation starts from a requested restore timestamp, builds an independent graph state, and then advances through the same domain events that power the live system. Live state, historical state, and future projected state all use the same event semantics, but they run in separate storage contexts so one mode cannot leak into another.

When a simulation is created, the backend creates an in-memory OrientDB database for topology and entity state, and Redis stores hot item state under simulation-specific keys. `DatabaseContextHolder` carries the active simulation id through the processing path, so the same repositories and event processors can operate on either live storage or the simulation namespace.

Historical restoration is built from ClickHouse. The backend restores the latest available snapshot before the requested timestamp, replays ClickHouse events after that snapshot, and then processes any generated internal movement events needed to project item state up to the restore point. ClickHouse remains the immutable event store; simulation replay does not write projected events back as live history.

Playback combines two event streams:

- **External events** are persisted domain events from ClickHouse, such as item creation, sensor position updates, conveyor changes, and topology changes.
- **Internal events** are scheduled movement events generated by the simulation logic, such as an item reaching the end of a conveyor when no real sensor event has arrived yet.

During playback, `HistoricalEventPlayer` loads external events only up to physical now, while internal events can continue beyond physical now for future projection. Events are processed in timestamp order. If an external event and an internal event have the same timestamp, the external ClickHouse event wins so real history can replace a prediction before it fires.

The simulation clock is persisted as `lastProcessedTimestamp`. Playback advances in small windows and only checkpoints a window after that simulation time is actually due. This keeps pause, resume, and speed changes anchored to the real backend playback position instead of a future preloaded window.

Playback speed changes are rescheduled through the same pause/resume cancellation path. When speed changes while a simulation is `PLAYING`, the backend cancels the active playback worker as an internal reschedule, keeps the simulation status as `PLAYING`, checkpoints active conveyor items at the current playback anchor, recalculates item movement schedules, and starts a new playback worker from the same timestamp with the new speed. When speed changes while the simulation is `READY`, `PAUSED`, or `STOPPED`, the backend only stores and broadcasts the new speed; it does not start playback.

### AI Docs [![Ask DeepWiki](https://deepwiki.com/badge.svg)](https://deepwiki.com/SimoneErba/Flunav)
