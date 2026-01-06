GEMINI_MODEL="gemini-3.0-pro" gemini

### TODO

TEST:

allow items to move in the middle of a coveyour. not just locations. add a param maybe in the event, edge or lcoation. 

---

TODO:

color input is out of the page
simualtin is not filled with items
attese, ricircoli

see other simulations (with a name, be able to switch to them)
eiting te graph is just for the initiala setup, remove items while in edit mode
edit active simulations to see "what if"
outlet box: save to orient db only if websocket can be sent (transaction)
page to see item history
chatbot to ask about item history or location events
puppygraph or ckickgraph for analytics (BI) - 

            
                //if we dont receive a item position update when we expect it (for item reaching their destination for example)
                // we can reput them on the system on the main path


Domain A: The Production Line (e.g., Automotive, Food Manufacturing)
    Core Model: A "Flow Shop." It's a deterministic, linear, and predictable process.
    Primary Goal: Throughput and Efficiency. The main questions are "How fast are we making things?" and "Where are the bottlenecks?"
    Item Behavior: Items are passive. They are "spawned" at the beginning and follow a single, pre-defined path. Their identity is often tied to the batch.
    Key Features:
    Virtual Spawners: Creating items based on a production rate.
    Bottleneck Analysis: Visualizing queues and buffers.
    OEE (Overall Equipment Effectiveness) Tracking: Visualizing machine states (Running, Stopped, Jammed).
    Linear Itinerary: The path is fixed.
Domain B: The Sorting Plant (e.g., Logistics Hub, Mail Center, Baggage Handling)
    Core Model: A "Job Shop." It's a dynamic, decision-rich, and event-driven process.
    Primary Goal: Accuracy and Routing. The main question is "Is the right item getting to the right place?"
    Item Behavior: Items are active agents. Each item has a unique identity and a specific, dynamic destination. It arrives with a "goal," and the system must make decisions to get it there.
    Key Features:
    Directed Itinerary: Calculating the path for an item to its specific destination.
    Decision Points: Visualizing switches, diverters, and "pushes."
    Error Handling: The "Return to Loop" logic for failed exits is critical here.
    Real-time Tracking: Events are often per-item (barcode scans at every junction).


### Known Challenges

*   **State Divergence Risk:** A bug in the `EventProcessor` could cause the OrientDB state to drift out of sync with the ClickHouse event log.
*   **Event Schema Evolution:** How do we handle changes to event structures over time without breaking our ability to replay old events?
*   **Operational Complexity:** The "Big Bang" restore process is powerful but requires system downtime.
*   **Debugging Complexity:** Tracing the state of a single entity requires querying the event log, not just the current state database.

### The Roadmap: Hardening for Production

#### 🚀 **2. Scale Real-Time Broadcasts with Redis Pub/Sub**
edis pub sub if ackend is multi process

#### 🚀 **3. Implement Event Versioning & Upcasting**
*   **Goal:** Future-proof our event log against schema changes.
*   **Action:** Develop a strategy for versioning our event DTOs. Implement "upcaster" functions that can transparently transform older versions of an event into the current version during deserialization, ensuring replays never fail.

#### 🚀 **4. Build Robust Monitoring & Dead-Letter Queues**
*   **Goal:** Prevent the "split brain" problem and handle processing failures gracefully.
*   **Action:** Integrate a message queue (like RabbitMQ or Kafka) for event processing. If an event fails to be processed after several retries, it will be moved to a **Dead-Letter Queue (DLQ)** for manual inspection by an operator. This stops a single poison pill event from halting the entire system.

#### 🚀 **5. Develop an Event Forensics Tool**
*   **Goal:** Simplify debugging.
*   **Action:** Build an internal admin dashboard that allows a developer to input an `itemId` or `locationId` and see a full, chronological stream of every event that has ever affected that entity.



# Flunav Anomaly & Fault Detection Algorithms
This document outlines the core algorithms for transforming raw item flow data into actionable, predictive insights. The system is designed in two phases: an offline "Learning" phase to establish baselines from historical data, and a real-time "Detection" phase to analyze live events.
1. Statistical Baselining Engine (The "Learner")
This engine runs offline (e.g., nightly or on-demand) to query the historical event log in ClickHouse and build a statistical model of normal operation.
Core Metrics to Calculate:

Path-Level Baselines: For each unique path (e.g., Location-A -> Location-B):
avg_transit_time: The average time for an item to travel between the two locations.
stddev_transit_time: The standard deviation of the transit time, measuring its normal variance.
median_transit_time: The 50th percentile of transit time, robust to outliers.

Location-Level Baselines: For each unique location:
avg_dwell_time: The average time an item spends at this location.
stddev_dwell_time: The standard deviation of dwell time.
avg_throughput_per_minute: The average number of items that pass through this location per minute.
stddev_throughput_per_minute: The standard deviation of throughput, measuring its stability.

Implementation:
Use ClickHouse window functions (lagInFrame, PARTITION BY entity_id) to calculate individual transit times from the raw event stream.
Use ClickHouse aggregation functions (avg, stddevPop, quantile) grouped by path or location to compute the final baseline metrics.
Store the calculated baselines in a fast-access cache (e.g., Redis, or an in-memory map for the MVP).

2. Real-time Anomaly Detection Engine (The "Detector")
This engine analyzes each new ItemPositionChangedEvent in real-time by comparing it against the learned baselines.
Algorithm 1: "System Pressure" & Predictive Bottleneck Detection
Concept: Detects forming jams by measuring the rate of change of a location's queue size. A rising "pressure" is a powerful leading indicator of a bottleneck.
Metric: Pressure = (Rate of Item Arrivals) - (Rate of Item Departures) over a short time window (e.g., 1 minute).
Trigger: Alert if Pressure remains consistently positive for several consecutive windows.
Predicts: Imminent product jams and blockages before they bring the system to a halt.
Algorithm 2: "Flow Turbulence" & Component Health Monitoring
Concept: Measures the stability and consistency of the item flow. A healthy component has a smooth, predictable flow (low turbulence); a failing one has a chaotic, volatile flow (high turbulence).
Metric: Turbulence = stddev(throughput_per_minute) over a recent time window (e.g., the last 10 minutes).
Trigger: Alert if Turbulence exceeds a pre-defined threshold or its own historical baseline.
Predicts: Unstable or "flapping" sensors, stuttering motors, control system bugs, and other intermittent faults that simple averages would miss.
Algorithm 3: Z-Score Anomaly Detection for Timing
Concept: A standard statistical method to identify outliers. It measures how many standard deviations a data point is from the mean.
Metric: Z-Score = (current_value - baseline_avg) / baseline_stddev
Triggers:
Transit Time: Alert if Z-Score for the transit time between A and B is > 3. Predicts motor wear, belt slippage, or increased friction.
Dwell Time: Alert if Z-Score for the dwell time at a location is > 3. Predicts a forming jam or a faulty exit sensor.
Predicts: Gradual mechanical degradation and sudden blockages.
Algorithm 4: Graph-Based Path & Sequence Validation
Concept: Uses the known physical layout (the graph) of the conveyor system to detect logical errors.
Triggers:
Invalid Path: An event shows an item moved A -> X when X is not a valid destination from A in the graph.
Out-of-Sequence (Skipped Sensor): An item is detected at C when its last known location was A, having never been detected at the intermediate location B.
Predicts: Diverter/gate malfunctions, barcode/RFID reader errors, and dead or non-responsive sensors.
Algorithm 5: "Shockwave" & Root Cause Propagation Analysis
Concept: When a major failure occurs, its effects (jams, slowdowns) propagate through the system like a wave. This algorithm tracks the propagation to find the origin.
Method:
Detect a primary, high-severity anomaly (e.g., a "Pressure" alert).
Record the location_id and timestamp of the initial event.
Traverse the graph downstream.
Query the time-series data for each downstream location to find when a correlated anomaly appeared.
The location with the earliest timestamp is the likely root cause.
Provides: Powerful diagnostic insight to help maintenance teams find the source of a system-wide failure instantly.

# BI

1. Operational Efficiency & Optimization
This category answers the question: "We're not broken, but how can we be faster and more efficient?"
A) OEE (Overall Equipment Effectiveness) Calculation
What It Is: OEE is the gold-standard KPI for manufacturing and logistics. It measures the percentage of planned production time that is truly productive. It's a score calculated as Availability x Performance x Quality.
How Flunav Does It:
Availability: You can calculate this easily. It's the time a conveyor is actually moving items vs. the time it was scheduled to run. A stopped conveyor (zero throughput) is "unavailable."
Performance: You have the data for this. It's the actual throughput vs. the maximum theoretical throughput (which you can calculate from your baselines). A conveyor running slower than its baseline has low performance.
Quality: This is the magic part. You can provide an API for the client to push "quality" data into Flunav (e.g., {"item_id": "Item-123", "status": "REJECTED"}). Now you can correlate flow issues with quality problems.
Value: You move from a custom tool to speaking the universal language of operations managers. This feature alone makes Flunav instantly understandable and valuable to a huge market.
B) Critical Path Analysis
What It Is: In any complex system, there is always one path that is the primary bottleneck for the entire system's throughput. This is the "critical path."
How Flunav Does It: You have the entire system mapped as a graph in OrientDB and you have the avg_transit_time for every edge from your baseline analysis. You can run a graph traversal algorithm (like Dijkstra's, but modified to find the "heaviest" or slowest path) to identify the sequence of locations that has the longest cumulative transit time.
Value: This is incredibly actionable. You can tell a manager: "Improving the speed of any other conveyor will have minimal impact. But if you can speed up the path from Sorter-2 to Packing-4 by 10%, you will increase your entire facility's output by 8%." This justifies capital investment.
2. Advanced Analytics & Business Intelligence
This category answers the question: "How are we performing over time, and where should we focus our strategic efforts?"
A) Comparative Analysis & Benchmarking
What It Is: Dashboards that allow managers to compare performance across different contexts.
How Flunav Does It: Your ClickHouse data is perfect for this. You can build queries that GROUP BY different dimensions:
Shift Performance: Compare the throughput, jam frequency, and OEE of the morning shift vs. the night shift.
Line Performance: If a factory has two identical production lines, you can overlay their KPIs to see which one is performing better and why.
Time-Based Performance: Compare performance this quarter vs. last quarter to track the impact of improvements.
Value: Turns Flunav into a management tool for identifying best practices and underperforming teams or equipment.
B) Component Reliability Reporting (The "Bad Actor" Report)
What It Is: A report that ranks every location/component by its negative impact on the system.
How Flunav Does It: Every time your anomaly detection engine generates an alert (Transit Time Anomaly, High Turbulence, etc.), you log it. This report is a simple query that COUNTs the number of alerts and groups them by location_id.
Value: This is a maintenance manager's dream. It gives them a data-driven "Top 10" list of the most problematic components in their entire facility. It helps them move from reactive "firefighting" to proactive, targeted maintenance.
3. Enhanced Simulation & "What-If" Scenarios
This category answers the question: "How can we test our ideas for improvement without risking downtime?"
A) "What-If" Scenario Modeling
What It Is: Allow a user to load a historical day's worth of data, but change the parameters of the system and re-run the simulation to see the outcome.
How Flunav Does It: The simulation engine would be modified to accept new parameters. For example, the user could say: "Run yesterday's data again, but what if Conveyor-B was 50% faster?" The simulator would process the same event stream, but when an item hits Conveyor-B, it would use the new, faster speed to calculate its transit time and project the impact on all downstream queues and bottlenecks.
Value: This is a virtual commissioning tool. It allows companies to test the ROI of expensive upgrades before they buy them. It's a massive, high-value feature.
B) Failure Impact Simulation
What It Is: Allow a user to simulate a failure at a specific point and watch how the system reacts.
How Flunav Does It: The user selects a location on the map (e.g., Sorter-1) and clicks "Simulate Failure." In the simulation, any item that reaches Sorter-1 is held there indefinitely. The user can then watch the "shockwave" of jams propagate backward through the system and see how long it takes for the entire line to grind to a halt.
Value: Excellent for operator training, developing contingency plans, and identifying hidden weaknesses in the system design.
4. Integration & Extensibility
This category answers the question: "How does Flunav fit into our existing data ecosystem?"
A) Data Export & BI Connector
What It Is: The ability for customers to get their data out of Flunav to use in their own tools.
How Flunav Does It:
Provide a simple API endpoint to export aggregated data (e.g., hourly throughput per location) as a CSV or Parquet file.
For enterprise clients, build a dedicated connector for BI tools like Tableau or Power BI. This connector would essentially be a service that translates requests from the BI tool into ClickHouse SQL queries.
Value: This makes the IT and data science teams love you. It shows that you are an open platform, not a closed data silo.
B) External Data Ingestion for Context
What It Is: The reverse of the above. An API that allows customers to push their own business data into Flunav to be displayed alongside the flow data.
How Flunav Does It: For example, a customer could push their order fulfillment data. When you click on an item in the Flunav UI, the sidebar could show not only its physical history but also the associated order_id, customer_name, and delivery_deadline.
Value: This transforms Flunav from a purely operational tool into a business visibility platform. A manager can now see not just a jam, but that the jam is holding up the order for their most important customer. This contextual information is immensely valuable.



AI instructions

avoid useless comments related to the promtp like "this was already correct". only add codmmens that exmplain the code. and add it just at the beginning of a method, or in the most compx parts. not on each line

avoid examples to explain concepts

always give full code files dont omit things for brevity