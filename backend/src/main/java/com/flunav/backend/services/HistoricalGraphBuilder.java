package com.flunav.backend.services;

import com.flunav.backend.context.DatabaseContextHolder;
import com.flunav.backend.models.graph.GraphData;
import com.flunav.backend.models.response.ConveyorResponse;
import com.flunav.backend.models.response.ItemResponse;
import com.flunav.backend.models.response.LocationResponse;
import com.flunav.backend.models.simulation.SimulationState;
import com.flunav.backend.models.simulation.SimulationStatus;
import com.flunav.backend.repositories.LiveItemRepository;
import com.flunav.backend.services.ClickHouseService.Snapshot;
import com.orientechnologies.orient.core.db.ODatabaseSession;
import com.orientechnologies.orient.core.id.ORID;
import com.orientechnologies.orient.core.record.OEdge;
import com.orientechnologies.orient.core.record.OVertex;
import flunav.events.DomainEvent;
import flunav.types.PositionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
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
    private final TimeService timeService;

    public HistoricalGraphBuilder(ClickHouseService clickHouseService, EventProcessor eventProcessor,
            OrientDBService orientDBService, SimulationService simulationService,
            LiveItemRepository liveItemRepository, TimeService timeService) {
        this.clickHouseService = clickHouseService;
        this.eventProcessor = eventProcessor;
        this.orientDBService = orientDBService;
        this.simulationService = simulationService;
        this.liveItemRepository = liveItemRepository;
        this.timeService = timeService;
    }

    @Async("taskExecutor")
    public void build(String simulationId, Instant restorePoint, Semaphore buildPermits) {
        try (var context = DatabaseContextHolder.enterSimulationContext(simulationId)) {
            logger.info("Starting historical graph build for simulation: {}", simulationId);
            Instant physicalNow = timeService.physicalNow();
            Instant realEventReplayEnd = restorePoint.isAfter(physicalNow) ? physicalNow : restorePoint;

            // 1. Restore from Snapshot (The Baseline)
            Optional<Snapshot> snapshotOpt = clickHouseService.getMostRecentSnapshotBefore(realEventReplayEnd);
            Instant eventsAfterTimestamp = Instant.EPOCH;

            if (snapshotOpt.isPresent()) {
                Snapshot snapshot = snapshotOpt.get();
                eventsAfterTimestamp = snapshot.timestamp();
                logger.info("Restoring state from snapshot taken at {}", eventsAfterTimestamp);
                simulationService.updateBuildProgress(simulationId, 0.0, eventsAfterTimestamp);
                for (DomainEvent mappingEvent : clickHouseService
                        .getLatestDestinationMappingEventsBefore(eventsAfterTimestamp)) {
                    eventProcessor.processEventWithoutBroadcast(mappingEvent);
                }
                restoreFromSnapshotData(snapshot.graphData());
            }

            // 2. Replay Events (The Delta)
            List<DomainEvent> eventsToReplay = eventsAfterTimestamp.isBefore(realEventReplayEnd)
                    ? new ArrayList<>(clickHouseService.getEventsBetween(eventsAfterTimestamp, realEventReplayEnd))
                    : List.of();
            eventsToReplay.sort(Comparator.comparing(DomainEvent::getTimestamp));
            logger.info("Found {} events to replay for simulation {}", eventsToReplay.size(), simulationId);

            Instant progressStart = determineProgressStart(snapshotOpt, eventsToReplay, realEventReplayEnd);
            BuildProgressTracker progressTracker = new BuildProgressTracker(
                    simulationId,
                    progressStart,
                    restorePoint,
                    simulationService);
            progressTracker.report(progressStart, true);

            replayEventsAndInternalQueue(simulationId, eventsToReplay, realEventReplayEnd, progressTracker);
            simulationService.checkpointSimulationAt(simulationId, realEventReplayEnd);
            progressTracker.report(realEventReplayEnd, false);

            if (restorePoint.isAfter(realEventReplayEnd)) {
                processInternalEventsUntil(simulationId, restorePoint, progressTracker);
            }
            logger.info("Historical graph build complete for simulation: {}", simulationId);
            simulationService.updateSimulationStatus(simulationId, SimulationStatus.READY, restorePoint);

        } catch (Exception e) {
            logger.error("A critical error occurred during the build process for simulation: {}", simulationId, e);
            simulationService.updateSimulationStatus(simulationId, SimulationStatus.FAILED, null);
        } finally {
            buildPermits.release();
            logger.info("Build permit released. Available permits: {}", buildPermits.availablePermits());
            simulationService.processWaitingQueue();
        }
    }

    private void replayEventsAndInternalQueue(String simulationId, List<DomainEvent> externalEvents,
            Instant replayEnd, BuildProgressTracker progressTracker) {
        SimulationState state = simulationService.getSimulationState(simulationId);

        orientDBService.withSession(session -> {
            int externalIndex = 0;

            while (externalIndex < externalEvents.size() || hasInternalEventDueAtOrBefore(state, replayEnd)) {
                DomainEvent nextExternal = externalIndex < externalEvents.size() ? externalEvents.get(externalIndex)
                        : null;
                DomainEvent nextInternal = state.getInternalEventQueue().peek();

                if (nextExternal != null && shouldProcessExternalBeforeInternal(nextExternal, nextInternal,
                        replayEnd)) {
                    processExternalEvent(nextExternal);
                    progressTracker.report(nextExternal.getTimestamp(), false);
                    externalIndex++;
                    continue;
                }

                DomainEvent processedInternal = simulationService.processNextInternalEvent(simulationId);
                if (processedInternal == null) {
                    break;
                }
                progressTracker.report(processedInternal.getTimestamp(), false);
            }
        });
    }

    private void processInternalEventsUntil(String simulationId, Instant targetTime,
            BuildProgressTracker progressTracker) {
        SimulationState state = simulationService.getSimulationState(simulationId);
        while (hasInternalEventDueAtOrBefore(state, targetTime)) {
            DomainEvent processedInternal = simulationService.processNextInternalEvent(simulationId);
            if (processedInternal == null) {
                break;
            }
            progressTracker.report(processedInternal.getTimestamp(), false);
        }

        simulationService.checkpointSimulationAt(simulationId, targetTime);
        progressTracker.report(targetTime, false);
    }

    private Instant determineProgressStart(Optional<Snapshot> snapshotOpt, List<DomainEvent> eventsToReplay,
            Instant replayEnd) {
        if (snapshotOpt.isPresent()) {
            return snapshotOpt.get().timestamp();
        }
        if (!eventsToReplay.isEmpty()) {
            return eventsToReplay.getFirst().getTimestamp();
        }
        return replayEnd;
    }

    private boolean hasInternalEventDueAtOrBefore(SimulationState state, Instant timestamp) {
        DomainEvent event = state.getInternalEventQueue().peek();
        return event != null && !event.getTimestamp().isAfter(timestamp);
    }

    private boolean shouldProcessExternalBeforeInternal(DomainEvent externalEvent, DomainEvent internalEvent,
            Instant replayEnd) {
        if (internalEvent == null || internalEvent.getTimestamp().isAfter(replayEnd)) {
            return true;
        }
        return !externalEvent.getTimestamp().isAfter(internalEvent.getTimestamp());
    }

    private void processExternalEvent(DomainEvent event) {
        try (var timeContext = timeService.enterVirtualTime(event.getTimestamp())) {
            eventProcessor.processEventWithoutBroadcast(event);
        } catch (Exception e) {
            logger.warn("Error while processing event {}. Skipping to the next one. Error: {}",
                    event.getEventType(), e.getMessage());
        }
    }

    private static final class BuildProgressTracker {
        private final String simulationId;
        private final Instant start;
        private final Instant target;
        private final SimulationService simulationService;
        private int lastPublishedPercent = -1;

        private BuildProgressTracker(String simulationId, Instant start, Instant target,
                SimulationService simulationService) {
            this.simulationId = simulationId;
            this.start = start;
            this.target = target;
            this.simulationService = simulationService;
        }

        private void report(Instant processedTimestamp, boolean force) {
            int percent = calculateBuildProgress(start, target, processedTimestamp);
            if (!force && percent <= lastPublishedPercent) {
                return;
            }

            lastPublishedPercent = percent;
            simulationService.updateBuildProgress(simulationId, percent, processedTimestamp);
        }
    }

    static int calculateBuildProgress(Instant start, Instant target, Instant processedTimestamp) {
        long totalMillis = target.toEpochMilli() - start.toEpochMilli();
        if (totalMillis <= 0) {
            return 100;
        }

        long processedMillis = processedTimestamp.toEpochMilli() - start.toEpochMilli();
        double progress = (processedMillis * 100.0) / totalMillis;
        return (int) Math.floor(Math.max(0.0, Math.min(100.0, progress)));
    }

    public void restoreFromSnapshotData(GraphData graphData) {
        orientDBService.withSession(session -> {
            logger.warn("Executing snapshot restore on context DB: {}", session.getName());
            try {
                session.begin();
                clearDatabase(session);

                Map<String, ORID> locationIdToRidMap = new HashMap<>();
                Map<String, ConveyorResponse> conveyorMap = new HashMap<>();
                Instant snapshotTimestamp = graphData.getTimestamp();

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
                        conveyorMap.put(convData.getId(), convData);
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
                        itemVertex.setProperty("destinations", itemData.getDestinations());
                        itemVertex.setProperty("selectedExitId", itemData.getSelectedExitId());
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
                        restoreItemToRedis(itemData, conveyorMap, snapshotTimestamp);
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

    private void restoreItemToRedis(ItemResponse itemData, Map<String, ConveyorResponse> conveyorMap,
            Instant snapshotTimestamp) {
        String positionId;
        PositionType type;
        double accumulatedDistance = 0.0;
        Instant effectiveTimestamp = snapshotTimestamp != null ? snapshotTimestamp : itemData.getEntryTimestamp();

        if (itemData.getCurrentEdgeId() != null) {
            positionId = itemData.getCurrentEdgeId();
            type = PositionType.CONVEYOR;
            ConveyorResponse conveyor = conveyorMap.get(positionId);
            if (conveyor != null && conveyor.getLength() != null && itemData.getProgress() != null) {
                accumulatedDistance = conveyor.getLength() * itemData.getProgress();
            }
        } else {
            positionId = itemData.getLocationId();
            type = PositionType.LOCATION;
        }

        if (positionId != null && effectiveTimestamp != null) {
            liveItemRepository.saveItemState(
                    itemData.getId(),
                    positionId,
                    type,
                    effectiveTimestamp,
                    accumulatedDistance,
                    itemData.getName(),
                    itemData.getDestinations(),
                    itemData.getSelectedExitId(),
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
