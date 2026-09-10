package com.flunav.backend.services;

import com.flunav.backend.context.DatabaseContextHolder;
import com.flunav.backend.context.AnomalyProcessingContext;
import com.flunav.backend.models.analytics.AnomalyProcessingMode;
import com.flunav.backend.models.graph.GraphData;
import com.flunav.backend.models.response.ConveyorResponse;
import com.flunav.backend.models.response.ItemResponse;
import com.flunav.backend.models.response.LocationResponse;
import com.flunav.backend.models.simulation.SimulationState;
import com.flunav.backend.models.simulation.SimulationStatus;
import com.flunav.backend.repositories.LiveItemRepository;
import com.flunav.backend.repositories.PathCacheRepository;
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
    private static final int REPLAY_PAGE_SIZE = 1000;

    private final ClickHouseService clickHouseService;
    private final EventProcessor eventProcessor;
    private final OrientDBService orientDBService;
    private final SimulationService simulationService;
    private final LiveItemRepository liveItemRepository;
    private final TimeService timeService;
    private final PathCacheRepository pathCacheRepository;
    private final com.flunav.backend.repositories.LiveConveyorRepository liveConveyorRepository;
    private final com.flunav.backend.repositories.LiveLocationRepository liveLocationRepository;

    public HistoricalGraphBuilder(ClickHouseService clickHouseService, EventProcessor eventProcessor,
            OrientDBService orientDBService, SimulationService simulationService,
            LiveItemRepository liveItemRepository, TimeService timeService, PathCacheRepository pathCacheRepository,
            com.flunav.backend.repositories.LiveConveyorRepository liveConveyorRepository,
            com.flunav.backend.repositories.LiveLocationRepository liveLocationRepository) {
        this.clickHouseService = clickHouseService;
        this.eventProcessor = eventProcessor;
        this.orientDBService = orientDBService;
        this.simulationService = simulationService;
        this.liveItemRepository = liveItemRepository;
        this.timeService = timeService;
        this.pathCacheRepository = pathCacheRepository;
        this.liveConveyorRepository = liveConveyorRepository;
        this.liveLocationRepository = liveLocationRepository;
    }

    /**
     * Reconstructs a simulation database at the requested restore point.
     * Real ClickHouse history is replayed only up to physical now, then future
     * state is projected from internal events so simulations never read future
     * live history.
     */
    @Async("taskExecutor")
    public void build(String simulationId, Instant restorePoint, Semaphore buildPermits) {
        try (var context = DatabaseContextHolder.enterSimulationContext(simulationId);
                var analyticsContext = AnomalyProcessingContext.enter(AnomalyProcessingMode.HISTORICAL_BUILD)) {
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
            ClickHouseService.EventPage firstEventPage = eventsAfterTimestamp.isBefore(realEventReplayEnd)
                    ? clickHouseService.getEventsBetweenPage(eventsAfterTimestamp, realEventReplayEnd, null,
                            REPLAY_PAGE_SIZE)
                    : new ClickHouseService.EventPage(List.of(), null, 0);
            List<DomainEvent> firstEvents = new ArrayList<>(firstEventPage.events());
            firstEvents.sort(Comparator.comparing(DomainEvent::getTimestamp));
            firstEventPage = new ClickHouseService.EventPage(firstEvents, firstEventPage.nextCursor(),
                    firstEventPage.rowCount());
            logger.info("Loaded first replay page with {} events for simulation {}", firstEvents.size(),
                    simulationId);

            Instant progressStart = determineProgressStart(snapshotOpt, firstEvents, realEventReplayEnd);
            BuildProgressTracker progressTracker = new BuildProgressTracker(
                    simulationId,
                    progressStart,
                    restorePoint,
                    simulationService);
            simulationService.initializeAnomalySchedule(simulationId, progressStart);
            progressTracker.report(progressStart, true);

            replayEventsAndInternalQueue(simulationId, firstEventPage, eventsAfterTimestamp, realEventReplayEnd,
                    progressTracker);
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

    /**
     * Merges replayed external events with projected internal movement events.
     * External events win timestamp ties so observed history can override scheduled
     * projections before those projections are applied.
     */
    private void replayEventsAndInternalQueue(String simulationId, ClickHouseService.EventPage firstPage,
            Instant replayStart, Instant replayEnd, BuildProgressTracker progressTracker) {
        SimulationState state = simulationService.getSimulationState(simulationId);

        orientDBService.withSession(session -> {
            ClickHouseService.EventPage currentPage = firstPage;
            int externalIndex = 0;

            while (true) {
                if (externalIndex >= currentPage.events().size() && currentPage.hasMore(REPLAY_PAGE_SIZE)) {
                    currentPage = clickHouseService.getEventsBetweenPage(replayStart, replayEnd,
                            currentPage.nextCursor(), REPLAY_PAGE_SIZE);
                    externalIndex = 0;
                }

                boolean externalAvailable = externalIndex < currentPage.events().size();
                boolean internalAvailable = hasInternalEventDueAtOrBefore(state, replayEnd);
                if (!externalAvailable && !internalAvailable) {
                    break;
                }

                DomainEvent nextExternal = externalAvailable ? currentPage.events().get(externalIndex) : null;
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

    /**
     * Projects queued internal events from physical now to the future restore point.
     * A final checkpoint records item physics at the exact target time even if the
     * last processed event happened earlier.
     */
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

    /**
     * Decides replay ordering between real history and projected movement.
     * ClickHouse events are processed first on equal timestamps because real
     * observations should cancel or replace predictions made earlier.
     */
    private boolean shouldProcessExternalBeforeInternal(DomainEvent externalEvent, DomainEvent internalEvent,
            Instant replayEnd) {
        if (internalEvent == null || internalEvent.getTimestamp().isAfter(replayEnd)) {
            return true;
        }
        return !externalEvent.getTimestamp().isAfter(internalEvent.getTimestamp());
    }

    /**
     * Applies one historical event under virtual time without broadcasting.
     * Replay updates the simulation's derived stores but must not look like a live
     * operator update to connected clients.
     */
    private void processExternalEvent(DomainEvent event) {
        try (var timeContext = timeService.enterVirtualTime(event.getTimestamp())) {
            eventProcessor.processEventWithoutBroadcast(event);
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

    /**
     * Restores the OrientDB and Redis baseline from a ClickHouse graph snapshot.
     * OrientDB rebuilds durable topology and item metadata while Redis receives hot
     * item positions so GraphService can project movement immediately after replay.
     */
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
                        locationVertex.setProperty("timeToProcessMs", locData.getTimeToProcessMs());

                        if (locData.getType() != null) {
                            locationVertex.setProperty("type", locData.getType().name());
                        }
                        if (locData.getProperties() != null) {
                            locationVertex.setProperty("properties", locData.getProperties());
                        }
                        locationVertex.setProperty("activeAlarms", List.of());

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
                            conveyorEdge.setProperty("name", convData.getName());
                            conveyorEdge.setProperty("minDistance", convData.getMinDistance());
                            conveyorEdge.setProperty("capacity", convData.getCapacity());
                            conveyorEdge.setProperty("properties", convData.getProperties());
                            conveyorEdge.setProperty("operatorEnabled", convData.getOperatorEnabled());

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
                        itemVertex.setProperty("priority", itemData.getPriority());

                        // Restore Physics State
                        // In the simulation DB, we store these as properties on the Vertex
                        // because we don't have Redis here.
                        itemVertex.setProperty("currentEdgeId", itemData.getCurrentEdgeId());
                        itemVertex.setProperty("destinations", itemData.getDestinations());
                        itemVertex.setProperty("selectedExitId", itemData.getSelectedExitId());
                        itemVertex.setProperty("routingStatus", itemData.getRoutingStatus());
                        itemVertex.setProperty("routingStatusUpdatedAt", itemData.getRoutingStatusUpdatedAt());
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
        pathCacheRepository.invalidateCurrentNamespace();
    }

    /**
     * Rehydrates a snapshot item into Redis hot state.
     * Conveyor progress is converted back to accumulated distance because movement
     * projection depends on distance checkpoints rather than rendered progress.
     */
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
                    itemData.getRoutingStatus(),
                    itemData.getRoutingStatusUpdatedAt(),
                    itemData.getPath());
            if (type == PositionType.CONVEYOR) {
                // Membership drives accumulation scheduling. Preserve leading-item order even on stopped belts.
                liveConveyorRepository.addItemToConveyor(positionId, itemData.getId(),
                        effectiveTimestamp.minusMillis(Math.round(accumulatedDistance * 1000)));
            } else {
                liveLocationRepository.addItemToLocation(positionId, itemData.getId());
            }
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
