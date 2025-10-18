### TODO

see other simulations (with a name, be able to switch to them)
check the simulation heartbeat
item state colors and mapping
uscite, attese, ricircoli
edit active simulations to see "what if"

### Known Challenges

*   **State Divergence Risk:** A bug in the `EventProcessor` could cause the OrientDB state to drift out of sync with the ClickHouse event log.
*   **Event Schema Evolution:** How do we handle changes to event structures over time without breaking our ability to replay old events?
*   **Operational Complexity:** The "Big Bang" restore process is powerful but requires system downtime.
*   **Debugging Complexity:** Tracing the state of a single entity requires querying the event log, not just the current state database.

### The Roadmap: Hardening for Production

The following features are priorities for making the system operationally mature and ready for scale. The addition of **Redis** is a key enabler for many of these.

#### 🚀 **1. Implement a High-Performance Caching Layer with Redis**
*   **Goal:** Dramatically reduce read load on OrientDB and improve API response times.
*   **Action:** Use Redis as a cache-aside layer for expensive queries like `getGraphData()`. An event processor would invalidate the cache upon state changes, ensuring data remains fresh.

#### 🚀 **2. Scale Real-Time Broadcasts with Redis Pub/Sub**
*   **Goal:** Ensure WebSocket updates are delivered to all users, even when the backend is running on multiple servers.
*   **Action:** Configure the WebSocket messaging layer to use Redis Pub/Sub as a message broker. This makes our real-time layer stateless and horizontally scalable.

#### 🚀 **3. Implement Event Versioning & Upcasting**
*   **Goal:** Future-proof our event log against schema changes.
*   **Action:** Develop a strategy for versioning our event DTOs. Implement "upcaster" functions that can transparently transform older versions of an event into the current version during deserialization, ensuring replays never fail.

#### 🚀 **4. Build Robust Monitoring & Dead-Letter Queues**
*   **Goal:** Prevent the "split brain" problem and handle processing failures gracefully.
*   **Action:** Integrate a message queue (like RabbitMQ or Kafka) for event processing. If an event fails to be processed after several retries, it will be moved to a **Dead-Letter Queue (DLQ)** for manual inspection by an operator. This stops a single poison pill event from halting the entire system.

#### 🚀 **5. Develop an Event Forensics Tool**
*   **Goal:** Simplify debugging.
*   **Action:** Build an internal admin dashboard that allows a developer to input an `itemId` or `locationId` and see a full, chronological stream of every event that has ever affected that entity.