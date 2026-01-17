# FLUMEN Project Technical Summary

This document provides a technical overview of the FLUMEN project, intended for an AI coding agent.

## 1. Project Overview

-   **What it is:** A real-time Digital Twin platform for logistics and sorting systems (e.g., conveyor belts).
-   **Core Goal:** To visualize the physical flow of items, predict their paths, detect anomalies, and enable historical analysis ("Time Travel") through simulations.
-   **Architecture Style:** Decoupled Frontend (React) and Backend (Java) applications, running in Docker containers.

## 2. Core Technologies

-   **Backend:** Java 21, Spring Boot 3
-   **Frontend:** React, Vite, TypeScript, Tailwind CSS
-   **Databases:**
    -   **OrientDB (Graph DB):** Stores the system's topology (nodes, edges) for both live and simulation states.
    -   **Redis:** Stores the "hot" real-time state (live item positions) and manages simulation queues.
    -   **ClickHouse (Columnar DB):** Acts as the immutable Event Store, the single source of truth for all historical events.
-   **Messaging:** RabbitMQ for decoupling API ingestion from event processing.
-   **Infrastructure:** Docker Compose, Nginx (as a reverse proxy).

## 3. Backend Architecture (Java / Spring Boot)

The backend is built on Event Sourcing and Polyglot Persistence principles.

### 3.1. Key Services & Components

This is a list of the most important classes and their responsibilities:

| Class | Responsibility |
| :--- | :--- |
| **`EventProcessor.java`** | **The brain of the system.** It contains the core logic for processing `DomainEvent` objects. It uses an internal, asynchronous, and order-guaranteed mechanism to handle events. It calls other services to apply changes to the databases. |
| **`OrientDBService.java`** | Manages all connections to OrientDB. Its `getSession()` method is context-aware: it checks `DatabaseContextHolder` to decide whether to return a session for the main database or for a specific in-memory simulation database. It also manages transactions. |
| **`LiveItemRepository.java`** | The data access layer for Redis. It handles saving, updating, and retrieving the real-time state of items. It uses a namespacing strategy (`sim:<id>:<key>`) to isolate simulation data from live data within Redis. |
| **`HistoricalGraphBuilder.java`** | Responsible for the "Time Travel" feature. It is an `@Async` worker that: 1. Restores a graph state from a ClickHouse snapshot. 2. Replays subsequent events from ClickHouse by calling `EventProcessor` to bring the simulation to the desired point in time. |
| **`SimulationService.java`** | Manages the lifecycle of simulations (Create, Start, Stop). It uses a `Semaphore` to limit concurrent builds and orchestrates the `HistoricalGraphBuilder`. |
| **`GraphService.java`** | Prepares the `GraphData` object for the frontend API (`GET /api/graph`). It's context-aware: for live views, it gets item data from Redis; for simulation views, it gets data directly from the simulation's OrientDB. |
| **`ItemService.java`** | Domain service that handles the business logic for creating, updating, and deleting items within OrientDB. It contains the logic for atomicity between OrientDB and Redis writes. |
| **`LocationService.java`** | Domain service for managing locations (nodes) in the graph. |
| **`SettingsController.java`** | API endpoint for managing visualization rules, which are then used by the `RuleEngineService`. |
| **`RuleEngineService.java`** | In-memory engine that applies visualization rules (e.g., `IF weight > 50 THEN color=red`) to `ItemResponse` DTOs before they are sent to the frontend. |

### 3.2. Data Flow (Live Event)

1.  External system sends a request to a **Controller** (e.g., `ItemController`).
2.  The Controller publishes a `DomainEvent` to **RabbitMQ** and immediately returns `202 Accepted`.
3.  An **`ItemEventListener`** consumes the message from RabbitMQ.
4.  The listener calls **`EventProcessor.process()`**.
5.  `EventProcessor` executes the business logic, calling services like `ItemService` and `LiveItemRepository` to update **OrientDB** and **Redis**.
6.  The changes are broadcast to connected clients via **WebSocket**.

## 4. Frontend Architecture (React / TypeScript)

The frontend is a Single Page Application (SPA) built with Vite.

### 4.1. Key Components & Hooks

| Component / Hook | Responsibility |
| :--- | :--- |
| **`App.tsx`** | The main application component. It manages the global layout, including the header and the main content area. It does **not** use a router; it uses conditional rendering or an overlay drawer for different views. |
| **`DisplayGraph.tsx`** | The core visualization component. It wraps the `<SigmaContainer>` and is responsible for rendering the graph canvas. |
| **`useGraphInteractions.ts`** | A critical custom hook that contains all the logic for user interaction with the graph: node clicking, dragging, edge creation, and the "Paradox-style" hover effect. |
| **`SettingsDrawer.tsx`** | An overlay panel that slides in from the side to display the settings view, allowing the user to configure visualization rules without losing the context of the main graph. |
| **`PropertiesEditor.tsx`** | A reusable form component for editing the key-value properties of any entity (Item, Location, Edge). |
| **`NodeEditor.tsx` / `EdgeEditor.tsx`** | Specific panels for editing the main attributes of nodes and edges. They use `PropertiesEditor` internally. |

## 5. How to Run the Project

### Full Stack (Docker)
The entire application can be started with a single command from the project root.
```bash
docker-compose up -d
```

### Local Development (Backend)
Navigate to the backend directory and run the Spring Boot application.
```bash
/usr/bin/env /home/dimin/.sdkman/candidates/java/21.0.2-tem/bin/java -agentlib:jdwp=transport=dt_socket,server=n,suspend=y,address=localhost:34285 @/tmp/cp_dsobqpp3eb85efhmckyfpv3o2.argfile com.flunav.backend.BackendApplication 22
```

### Local Development (Frontend)
Navigate to the frontend directory, install dependencies, and start the Vite dev server.
```bash
cd frontend
npm install
npm run dev
```

### AI instructions

avoid useless comments related to the promtp like "this was already correct". only add codmmens that exmplain the code. and add it just at the beginning of a method, or in the most compx parts. not on each line

avoid examples to explain concepts

always give full code files dont omit things for brevity