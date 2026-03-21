package com.flunav.backend.services;

import com.flunav.backend.context.DatabaseContextHolder;
import com.flunav.backend.models.graph.GraphData;
import com.flunav.backend.models.response.ConveyorResponse;
import com.flunav.backend.models.response.ItemResponse;
import com.flunav.backend.models.response.LocationResponse;
import com.flunav.backend.models.simulation.SimulationStatus;
import com.flunav.backend.repositories.LiveItemRepository;
import com.flunav.backend.services.ClickHouseService.Snapshot;
import com.orientechnologies.orient.core.db.ODatabaseSession;
import com.orientechnologies.orient.core.id.ORID;
import com.orientechnologies.orient.core.record.OEdge;
import com.orientechnologies.orient.core.record.OVertex;
import flunav.events.DomainEvent;
import flunav.events.EntityEvent;
import flunav.types.PositionType;
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
    private final LiveItemRepository liveItemRepository;

    public HistoricalGraphBuilder(ClickHouseService clickHouseService, EventProcessor eventProcessor,
            OrientDBService orientDBService, SimulationService simulationService,
            LiveItemRepository liveItemRepository) {
        this.clickHouseService = clickHouseService;
        this.eventProcessor = eventProcessor;
        this.orientDBService = orientDBService;
        this.simulationService = simulationService;
        this.liveItemRepository = liveItemRepository;
    }

    @Async("taskExecutor")
    public void build(String simulationId, Instant restorePoint, Semaphore buildPermits) {
        try (var context = DatabaseContextHolder.enterSimulationContext(simulationId)) {
            logger.info("Starting historical graph build for simulation: {}", simulationId);

            // 1. Restore from Snapshot (The Baseline)
            Optional<Snapshot> snapshotOpt = clickHouseService.getMostRecentSnapshotBefore(restorePoint);
            Instant eventsAfterTimestamp = Instant.EPOCH;

            if (snapshotOpt.isPresent()) {
                Snapshot snapshot = snapshotOpt.get();
                eventsAfterTimestamp = snapshot.timestamp();
                logger.info("Restoring state from snapshot taken at {}", eventsAfterTimestamp);
                restoreFromSnapshotData(snapshot.graphData());
            }

            // 2. Replay Events (The Delta)
            List<DomainEvent> eventsToReplay = clickHouseService.getEventsBetween(eventsAfterTimestamp, restorePoint);
            logger.info("Found {} events to replay for simulation {}", eventsToReplay.size(), simulationId);

            orientDBService.withSession(session -> {
                for (DomainEvent event : eventsToReplay) {
                    try {
                        eventProcessor.processEventWithoutBroadcast(event);
                    } catch (Exception e) {
                        logger.warn("Error while processing event {}. Skipping to the next one. Error: {}",
                                event.getEventType(), e.getMessage());
                    }
                }
            });

            logger.info("Historical graph build complete for simulation: {}", simulationId);
            simulationService.updateSimulationStatus(simulationId, SimulationStatus.READY, Instant.now());

        } catch (Exception e) {
            logger.error("A critical error occurred during the build process for simulation: {}", simulationId, e);
            simulationService.updateSimulationStatus(simulationId, SimulationStatus.FAILED, Instant.now());
        } finally {
            buildPermits.release();
            logger.info("Build permit released. Available permits: {}", buildPermits.availablePermits());
            simulationService.processWaitingQueue();
        }
    }

    public void restoreFromSnapshotData(GraphData graphData) {
        orientDBService.withSession(session -> {
            logger.warn("Executing snapshot restore on context DB: {}", session.getName());
            try {
                session.begin();
                clearDatabase(session);

                Map<String, ORID> locationIdToRidMap = new HashMap<>();

                // --- 1. RESTORE LOCATIONS (Nodes) ---
                if (graphData.getLocations() != null) {
                    for (LocationResponse locData : graphData.getLocations()) {
                        OVertex locationVertex = session.newVertex("Location");

                        locationVertex.setProperty("customId", locData.getId());
                        locationVertex.setProperty("name", locData.getName());
                        locationVertex.setProperty("active", locData.getActive());
                        locationVertex.setProperty("latitude", locData.getLatitude());
                        locationVertex.setProperty("longitude", locData.getLongitude());
                        locationVertex.setProperty("capacity", locData.getCapacity());

                        if (locData.getType() != null) {
                            locationVertex.setProperty("type", locData.getType().name());
                        }
                        if (locData.getProperties() != null) {
                            locationVertex.setProperty("properties", locData.getProperties());
                        }

                        locationVertex.save();
                        locationIdToRidMap.put(locData.getId(), locationVertex.getIdentity());
                    }
                }

                // --- 2. RESTORE CONVEYORS (Edges) ---
                // Note: GraphData now contains ConveyorResponse, not ConnectionResponse
                if (graphData.getConveyors() != null) {
                    for (ConveyorResponse convData : graphData.getConveyors()) {
                        ORID sourceRid = locationIdToRidMap.get(convData.getSourceId());
                        ORID targetRid = locationIdToRidMap.get(convData.getTargetId());

                        if (sourceRid != null && targetRid != null) {
                            OVertex sourceVertex = session.load(sourceRid);
                            OVertex targetVertex = session.load(targetRid);

                            OEdge conveyorEdge = sourceVertex.addEdge(targetVertex, "Conveyor");

                            conveyorEdge.setProperty("customId", convData.getId());
                            conveyorEdge.setProperty("length", convData.getLength());
                            conveyorEdge.setProperty("speed", convData.getSpeed());
                            conveyorEdge.setProperty("active", convData.getActive());
                            conveyorEdge.setProperty("type", convData.getType());
                            conveyorEdge.setProperty("mainPath", convData.getMainPath());

                            conveyorEdge.save();
                        }
                    }
                }

                // --- 3. RESTORE ITEMS (Flat List) ---
                // Items are no longer nested inside locations in the new GraphData structure
                if (graphData.getItems() != null) {
                    for (ItemResponse itemData : graphData.getItems()) {
                        OVertex itemVertex = session.newVertex("Item");

                        itemVertex.setProperty("customId", itemData.getId());
                        itemVertex.setProperty("name", itemData.getName());
                        itemVertex.setProperty("active", itemData.getActive());

                        // Restore Physics State
                        // In the simulation DB, we store these as properties on the Vertex
                        // because we don't have Redis here.
                        itemVertex.setProperty("currentEdgeId", itemData.getCurrentEdgeId());
                        itemVertex.setProperty("destinationId", itemData.getDestinationId());
                        itemVertex.setProperty("locationId", itemData.getLocationId());
                        itemVertex.setProperty("path", itemData.getPath());

                        if (itemData.getEntryTimestamp() != null) {
                            itemVertex.setProperty("entryTimestamp", itemData.getEntryTimestamp());
                        }

                        if (itemData.getProperties() != null) {
                            itemVertex.setProperty("properties", itemData.getProperties());
                        }

                        itemVertex.save();

                        // Restore to Redis (for GraphService visibility)
                        restoreItemToRedis(itemData);
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

    private void restoreItemToRedis(ItemResponse itemData) {
        String positionId;
        PositionType type;

        if (itemData.getCurrentEdgeId() != null) {
            positionId = itemData.getCurrentEdgeId();
            type = PositionType.CONVEYOR;
        } else {
            positionId = itemData.getLocationId();
            type = PositionType.LOCATION;
        }

        if (positionId != null && itemData.getEntryTimestamp() != null) {
            liveItemRepository.saveItemState(
                    itemData.getId(),
                    positionId,
                    type,
                    itemData.getEntryTimestamp(),
                    0.0, // Default offset for snapshot restore
                    itemData.getName(),
                    itemData.getDestinationId(),
                    itemData.getPath());
        }
    }

    private void clearDatabase(ODatabaseSession session) {
        // Clean up the new schema elements
        session.command("DELETE FROM Conveyor UNSAFE"); // Was ConnectedTo
        session.command("DELETE FROM Item UNSAFE");
        session.command("DELETE FROM Location UNSAFE");
        // HasPosition is gone, so no need to delete it
    }
}