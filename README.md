<p align="center">
  <img src="logo.svg" alt="Fiumen Logo" width="350"/>
</p>
<p align="center">
  A real-time digital twin engine for tracking and simulating complex logistical systems.
</p>

---

> ⚠️ **Work in Progress**
>
> This project is under active development. The backend architecture and core functionalities are largely complete, while the frontend UI is undergoing further refinement.

## What is Fiumen?

Fiumen is a backend platform and visualization tool designed to create a **digital twin** of systems with moving assets, such as conveyor-based sorting facilities or production lines. It ingests event data from external control systems (e.g., PLCs), maintains a real-time graph model of the system's state, and provides tools for historical analysis and simulation.

The core purpose is to transform discrete sensor data into a continuous, understandable, and actionable view of the entire operation.

<p align="center">
  <!-- A GIF or screenshot of the UI would be effective here -->
  <img src="https://i.imgur.com/your-demo-gif.gif" alt="Fiumen in action"/>
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

Fiumen's architecture is designed for resilience, scalability, and data fidelity by separating concerns and using specialized data stores.

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

### AI Docs [![Ask DeepWiki](https://deepwiki.com/badge.svg)](https://deepwiki.com/SimoneErba/Fiumen)