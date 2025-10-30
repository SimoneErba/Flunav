package com.fiumen.backend.services;

import com.fiumen.backend.context.DatabaseContextHolder;
import com.fiumen.backend.models.graph.GraphData;
import com.fiumen.backend.models.response.ConnectionResponse;
import com.fiumen.backend.models.response.ItemResponse;
import com.fiumen.backend.models.response.LocationResponse;
import com.fiumen.backend.models.simulation.SimulationStatus;
import com.fiumen.backend.services.ClickHouseService.Snapshot;
import com.orientechnologies.orient.core.db.ODatabaseSession;
import com.orientechnologies.orient.core.id.ORID;
import com.orientechnologies.orient.core.record.OEdge;
import com.orientechnologies.orient.core.record.OVertex;
import fiumen.events.DomainEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private final SimulationService simulationService;

    public HistoricalGraphBuilder(ClickHouseService clickHouseService, EventProcessor eventProcessor,
                                  OrientDBService orientDBService, SimulationService simulationService) {
        this.clickHouseService = clickHouseService;
        this.eventProcessor = eventProcessor;
        this.orientDBService = orientDBService;
        this.simulationService = simulationService;
    }

    @Async("taskExecutor")
    public void build(String simulationId, Instant restorePoint, Semaphore buildPermits) {
        try (var context = DatabaseContextHolder.enterSimulationContext(simulationId)) {
            logger.info("Starting historical graph build for simulation: {}", simulationId);

            Optional<Snapshot> snapshotOpt = clickHouseService.getMostRecentSnapshotBefore(restorePoint);
            Instant eventsAfterTimestamp = Instant.EPOCH;

            if (snapshotOpt.isPresent()) {
                Snapshot snapshot = snapshotOpt.get();
                eventsAfterTimestamp = snapshot.timestamp();
                logger.info("Restoring state from snapshot taken at {}", eventsAfterTimestamp);
                restoreFromSnapshotData(snapshot.graphData());
            }

            List<DomainEvent> eventsToReplay = clickHouseService.getEventsBetween(eventsAfterTimestamp, restorePoint);
            logger.info("Found {} events to replay for simulation {}", eventsToReplay.size(), simulationId);

            // Wrap in a transaction to be faster (we dont commit every time). if the transaction becomes too big, breaks it into chunks (TODO)
            orientDBService.withTransaction(session -> {
                try {
                    session.begin();
                    for (DomainEvent event : eventsToReplay) {
                        try {
                            eventProcessor.processEventWithoutBroadcast(event);
                        } catch (Exception e) {
                            logger.warn("Error while processing event {}: {}", event.getEventType(), e);
                        }
                    }
                    session.commit();
                } catch (Exception e) {
                    session.rollback();
                    logger.error("Transaction failed, rolling back changes", e);
                }
            });

            logger.info("Historical graph build complete for simulation: {}", simulationId);
            simulationService.updateSimulationStatus(simulationId, SimulationStatus.READY);

        } catch (Exception e) {
            logger.error("A critical error occurred during the build process for simulation: {}", simulationId, e);
            simulationService.updateSimulationStatus(simulationId, SimulationStatus.FAILED);
        } finally {
            buildPermits.release();
            logger.info("Build permit released. Available permits: {}", buildPermits.availablePermits());
            simulationService.processWaitingQueue();
        }
    }

    private void restoreFromSnapshotData(GraphData graphData) {
        orientDBService.withSession(session -> {
            logger.warn("Executing snapshot restore on context DB: {}", session.getName());
            try {
                session.begin();
                clearDatabase(session);

                Map<String, ORID> locationIdToRidMap = new HashMap<>();

                for (LocationResponse locData : graphData.getLocations()) {
                    OVertex locationVertex = session.newVertex("Location");
                    
                    // --- PROPRIETÀ DELLA LOCATION COMPILATE ---
                    locationVertex.setProperty("customId", locData.getId());
                    locationVertex.setProperty("name", locData.getName());
                    locationVertex.setProperty("active", locData.getActive());
                    locationVertex.setProperty("latitude", locData.getLatitude());
                    locationVertex.setProperty("longitude", locData.getLongitude());
                    locationVertex.setProperty("length", locData.getLength());
                    locationVertex.setProperty("speed", locData.getSpeed());
                    locationVertex.setProperty("capacity", locData.getCapacity());
                    // Converte l'enum in stringa per la persistenza
                    if (locData.getType() != null) {
                        locationVertex.setProperty("type", locData.getType().name());
                    }
                    if (locData.getProperties() != null) {
                        locationVertex.setProperty("properties", locData.getProperties());
                    }
                    
                    locationVertex.save();
                    locationIdToRidMap.put(locData.getId(), locationVertex.getIdentity());

                    if (locData.getItems() != null) {
                        for (ItemResponse itemData : locData.getItems()) {
                            OVertex itemVertex = session.newVertex("Item");

                            // --- PROPRIETÀ DELL'ITEM COMPILATE ---
                            itemVertex.setProperty("customId", itemData.getId());
                            itemVertex.setProperty("name", itemData.getName());
                            itemVertex.setProperty("active", itemData.getActive());
                            itemVertex.setProperty("speed", itemData.getSpeed());
                            if (itemData.getProperties() != null) {
                                itemVertex.setProperty("properties", itemData.getProperties());
                            }

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
                        
                        if (connData.getProperties() != null) {
                            connectionEdge.setProperty("properties", connData.getProperties());
                        }
                        
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
        orientDBService.withSession(this::clearDatabase);
    }

    private void clearDatabase(ODatabaseSession session) {
        session.command("DELETE FROM ConnectedTo UNSAFE");
        session.command("DELETE FROM HasPosition UNSAFE");
        session.command("DELETE FROM Item UNSAFE");
        session.command("DELETE FROM Location UNSAFE");
    }
}