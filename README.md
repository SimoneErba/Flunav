<p align="center">
  <img src="logo.svg" alt="Flumen Logo" width="350"/>
</p>

**Flumen** is a real-time visualization engine for tracking moving objects across a dynamic graph. It's designed to receive positional data from an external system (like a factory PLC or logistics API) and render the movement in an interactive, user-configurable interface.

Imagine watching items flow along a complex conveyor belt system, seeing their status change in real-time, and being able to replay the data. That's the goal of Flumen.

> ⚠️ **Work in Progress**
>
> This project is currently under active development. The core backend functionalities are nearing completion, but the frontent still needs work.

---

### AI Docs [![Ask DeepWiki](https://deepwiki.com/badge.svg)](https://deepwiki.com/SimoneErba/Flumen)

## 💡 Core Concepts

Flumen's design is based on a few key principles:

### 1. Node-Centric Tracks
Unlike traditional graph visualizations where edges represent the path, in Flumen, **specialized `Location` nodes represent the actual tracks**, this helps in tracking the exact item position and progress on the section.
- **Nodes** can be simple points (e.g., a sensor) or tracks (`Location` nodes).
- **`Location` nodes** have properties like `length` and `speed`. When an item enters a `Location` node, the frontend animates its movement along that node's length at the specified speed.
- **Edges** simply define the directed flow, indicating the *next* node an item will move to.

### 2. Decoupled State: The Reconciliation Loop
Flumen cleanly separates the "source of truth" from the visualization.
1.  **External System:** The authoritative source that knows the true location of every item.
2.  **Flumen Backend:** Listens for events from the external system (e.g., "Item A has arrived at Location 2"). It maintains the official state.
3.  **Flumen Frontend:** When the backend confirms an item is on a `Location` node, the frontend begins an "optimistic" animation of the item moving along that node. This provides a smooth visual experience without needing constant updates. The item officially moves to the *next* node only when the backend receives another confirmation event.

### 3. Event Sourcing for History and Auditing
The entire system is built on an **[Event Sourcing](https://martinfowler.com/eaaDev/EventSourcing.html)** architecture. Instead of just storing the current state, we store an immutable sequence of all events that have ever happened (e.g., `NodeCreated`, `ItemMoved`, `NodeAttributeChanged`).
- **Full History:** Replay events to see the state of the system at any point in time.
- **Debugging & Auditing:** Provides a complete, unchangeable log of every action.
- **State Resilience:** If a process stops, the item's progress (e.g., percentage traveled along a `Location` node) can be precisely stored and resumed.

## ✨ Key Features

- **Real-time Visualization:** See objects move and states change instantly via WebSockets.
- **Dynamic Graph Editor:** Create, move, and configure nodes and their connections directly in the UI.
- **Stateful Animations:** The frontend animates object movement based on the `length` and `speed` attributes of `Location` nodes.
- **Status-Driven Visuals:** Nodes dynamically change their appearance (e.g., color) to reflect their state (`ok`, `alarm`, `inactive`).
- **Event-Sourced Backend:** A robust architecture that provides a full, replayable history of the system.