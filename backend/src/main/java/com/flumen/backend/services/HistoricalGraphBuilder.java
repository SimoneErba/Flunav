package com.flumen.backend.services;

import com.flumen.backend.context.DatabaseContextHolder;
import com.flumen.backend.models.graph.GraphData;
import com.flumen.backend.models.response.ConnectionResponse;
import com.flumen.backend.models.response.ItemResponse;
import com.flumen.backend.models.response.LocationResponse;
import com.flumen.backend.models.simulation.SimulationStatus;
import com.flumen.backend.services.ClickHouseService.Snapshot;
import com.orientechnologies.orient.core.db.ODatabaseSession;
import com.orientechnologies.orient.core.id.ORID;
import com.orientechnologies.orient.core.record.OEdge;
import com.orientechnologies.orient.core.record.OVertex;
import flumen.events.DomainEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Semaphore;

@Service
public class HistoricalGraphBuilder {

    private static final Logger logger = LoggerFactory.getLogger(HistoricalGraphBuilder.class);

    private final ClickHouseService clickHouseService;
    private final EventProcessor eventProcessor;
    private final OrientDBService orientDBService;
    private final SimulationService simulationService; // Added dependency to update status

    public HistoricalGraphBuilder(ClickHouseService clickHouseService, EventProcessor eventProcessor,
                                  OrientDBService orientDBService, SimulationService simulationService) {
        this.clickHouseService = clickHouseService;
        this.eventProcessor = eventProcessor;
        this.orientDBService = orientDBService;
        this.simulationService = simulationService;
    }

    @Async("taskExecutor")
    public void build(String simulationId, Instant restorePoint, Semaphore buildPermits) {
        // --- This entire block now operates within the context of the simulation ---
        DatabaseContextHolder.setSimulationId(simulationId);
        try {
            logger.info("Starting historical graph build for simulation: {}", simulationId);

            Optional<Snapshot> snapshotOpt = clickHouseService.getMostRecentSnapshotBefore(restorePoint);
            Instant eventsAfterTimestamp = Instant.EPOCH;

            if (snapshotOpt.isPresent()) {
                Snapshot snapshot = snapshotOpt.get();
                eventsAfterTimestamp = snapshot.timestamp();
                logger.info("Restoring state from snapshot taken at {}", eventsAfterTimestamp);
                restoreFromSnapshotData(snapshot.graphData()); // Session is no longer needed here
            } else {
                // If no snapshot, clear the DB to ensure it's truly empty.
                clearDatabase(); // Session is no longer needed here
                logger.info("No snapshot found. Replaying all events from the beginning.");
            }

            List<DomainEvent> eventsToReplay = clickHouseService.getEventsBetween(eventsAfterTimestamp, restorePoint);
            logger.info("Found {} events to replay for simulation {}", eventsToReplay.size(), simulationId);

            for (DomainEvent event : eventsToReplay) {
                // EventProcessor is now context-aware.
                eventProcessor.processHistoricalEvent(simulationId, event);
            }

            logger.info("Historical graph build complete for simulation: {}", simulationId);
            simulationService.updateSimulationStatus(simulationId, SimulationStatus.READY);

        } catch (Exception e) {
            logger.error("A critical error occurred during the build process for simulation: {}", simulationId, e);
            simulationService.updateSimulationStatus(simulationId, SimulationStatus.FAILED);
        } finally {
            // CRUCIAL: Always clear the context and release the permit.
            DatabaseContextHolder.clear();
            buildPermits.release();
            logger.info("Build permit released. Available permits: {}", buildPermits.availablePermits());
            // Trigger the service to check the queue for the next build job.
            simulationService.processWaitingQueue();
        }
    }

    // --- Private Helper Methods (Refactored to be context-aware) ---

    private void restoreFromSnapshotData(GraphData graphData) {
        // This method now implicitly uses the session provided by the context.
        orientDBService.withSession(session -> {
            logger.warn("Executing snapshot restore on context DB: {}", session.getName());
            try {
                session.begin();
                clearDatabase(session); // Pass session to the inner helper

                Map<String, ORID> locationIdToRidMap = new HashMap<>();

                for (LocationResponse locData : graphData.getLocations()) {
                    OVertex locationVertex = session.newVertex("Location");
                    locationVertex.setProperty("customId", locData.getId());
                    // ... set all other location properties ...
                    locationVertex.save();
                    locationIdToRidMap.put(locData.getId(), locationVertex.getIdentity());

                    if (locData.getItems() != null) {
                        for (ItemResponse itemData : locData.getItems()) {
                            OVertex itemVertex = session.newVertex("Item");
                            // ... set all item properties ...
                            itemVertex.save();
                            itemVertex.addEdge(locationVertex, "HasPosition").save();
                        }
                    }
                }

                for (ConnectionResponse connData : graphData.getConnections()) {
                    ORID sourceRid = locationIdToRidMap.get(connData.getSourceId());
                    ORID targetRid = locationIdToRidMap.get(connData.getTargetId());
                    if (sourceRid != null && targetRid != null) {
                        OVertex sourceVertex = (OVertex) session.load(sourceRid);
 OVertex targetVertex = (OVertex) session.load(targetRid);
                        OEdge connectionEdge = sourceVertex.addEdge(targetVertex, "ConnectedTo");
                        // ... set connection properties ...
                        connectionEdge.save();
                    }
                }
                session.commit();
                logger.info("Snapshot restore committed successfully for DB: {}", session.getName());
            } catch (Exception e) {
                logger.error("Error during snapshot restore for DB: {}. Rolling back.", session.getName(), e);
                session.rollback();
                throw new RuntimeException("Snapshot restore failed and was rolled back.", e);
            }
        });
    }

    private void clearDatabase() {
        orientDBService.withSession(this::clearDatabase); // Use the context-aware withSession
    }

    private void clearDatabase(ODatabaseSession session) {
        session.command("DELETE FROM ConnectedTo UNSAFE");
        session.command("DELETE FROM HasPosition UNSAFE");
        session.command("DELETE FROM Item UNSAFE");
        session.command("DELETE FROM Location UNSAFE");
    }


    public void rebuildGraphState(Instant timeToRestore) {
        logger.info("Starting graph state reconstruction...");

        try{           
            Optional<Snapshot> snapshotOpt = clickHouseService.getMostRecentSnapshotBefore(timeToRestore);

            Instant startTime = Instant.EPOCH;

            if (snapshotOpt.isPresent()) {
                Snapshot snapshot = snapshotOpt.get();
                logger.info("Restoring state from snapshot taken at {}", snapshot.timestamp());
                restoreFromSnapshotData(snapshot.graphData());
                startTime = snapshot.timestamp();
            } else {
                logger.info("No snapshot found. Replaying all events from the beginning.");
            }

            List<DomainEvent> eventsToReplay = clickHouseService.getEventsBetween(startTime, timeToRestore);
            logger.info("Found {} events to replay.", eventsToReplay.size());

            for (DomainEvent event : eventsToReplay) {
                eventProcessor.process(event, false); 
            }

            logger.info("Graph state reconstruction complete.");
        } catch (Exception e) {
            logger.error("A critical error occurred during the rebuild process.", e);
        }
    }
}