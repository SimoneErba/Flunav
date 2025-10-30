## Fiumen

this is a tool to visualize in real time objects moving in a graph. it's main goal is to model sorting and conveyor systems.
The db is OrientdDb, the backend is spring boot and the frontend is react with Sigma.js.
There is also a simulator app to simulate a sorting system.
Events can be sent with http or rabbit, each event is then stored in clickhouse and processed. the state is then updated in orientdb.
the grap db only keeps the current state of the system, while with clickhouse users can go back in time. we save the event and reprocess them.

