package com.flunav.backend.services;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.flunav.backend.context.DatabaseContextHolder;
import com.flunav.backend.models.simulation.SimulationState;
import com.flunav.backend.models.simulation.SimulationStatus;
import com.flunav.backend.models.simulation.SimulationKind;
import com.flunav.backend.models.simulation.LiveInputState;
import com.flunav.backend.models.multisimulation.MultiSimulationBaseline;
import com.flunav.backend.repositories.LiveItemRepository;
import com.flunav.backend.repositories.PathCacheRepository;
import com.flunav.backend.repositories.LiveSimulationRepository;
import com.flunav.backend.repositories.AnomalyObservationRepository;
import com.flunav.backend.repositories.support.MultiSimulationRuntimeStore;

import flunav.events.DomainEvent;
import flunav.types.PositionType;

import com.flunav.backend.services.SimulationService.SimulationRequest;
/** Owns creation, fork construction, build admission flow, and resource cleanup.
 * Runtime queues and metadata remain owned by SimulationRuntimeState; the facade
 * retains its public synchronization boundary and the sole cleanup schedule.
 */
@Service
final class SimulationLifecycleManager {
    private static final Logger logger = LoggerFactory.getLogger(SimulationLifecycleManager.class);
    private final SimulationService simulations;
    private final OrientDBService orientDBService;
    private final HistoricalGraphBuilder historicalGraphBuilder;
    private final WebSocketService webSocketService;
    private final LiveItemRepository liveItemRepository;
    private final LiveSimulationRepository liveSimulationRepository;
    private final ThroughputBucketService throughputBucketService;
    private final PathCacheRepository pathCacheRepository;
    private final ClickHouseService clickHouseService;
    private final RoutingCoordinator routingCoordinator;
    private final AnomalyObservationRepository anomalyObservationRepository;
    private final MultiSimulationRuntimeStore multiSimulationRuntimeStore;
    private final SimulationCapacityManager capacityManager;
    private final EventProcessor eventProcessor;
    private final TimeService timeService;
    private final GraphService graphService;
    private final SimulationInputService simulationInputService;
    private final LiveSystemScheduler liveSystemScheduler;
    private final SimulationRuntimeState runtimeState;

    SimulationLifecycleManager(@Lazy SimulationService simulations,
            OrientDBService orientDBService,
            @Lazy HistoricalGraphBuilder historicalGraphBuilder,
            WebSocketService webSocketService,
            LiveItemRepository liveItemRepository,
            LiveSimulationRepository liveSimulationRepository,
            @Lazy ThroughputBucketService throughputBucketService,
            PathCacheRepository pathCacheRepository,
            ClickHouseService clickHouseService,
            RoutingCoordinator routingCoordinator,
            AnomalyObservationRepository anomalyObservationRepository,
            MultiSimulationRuntimeStore multiSimulationRuntimeStore,
            SimulationCapacityManager capacityManager,
            @Lazy EventProcessor eventProcessor,
            TimeService timeService,
            @Lazy GraphService graphService,
            SimulationInputService simulationInputService,
            @Lazy LiveSystemScheduler liveSystemScheduler,
            SimulationRuntimeState runtimeState) {
        this.simulations = simulations;
        this.orientDBService = orientDBService;
        this.historicalGraphBuilder = historicalGraphBuilder;
        this.webSocketService = webSocketService;
        this.liveItemRepository = liveItemRepository;
        this.liveSimulationRepository = liveSimulationRepository;
        this.throughputBucketService = throughputBucketService;
        this.pathCacheRepository = pathCacheRepository;
        this.clickHouseService = clickHouseService;
        this.routingCoordinator = routingCoordinator;
        this.anomalyObservationRepository = anomalyObservationRepository;
        this.multiSimulationRuntimeStore = multiSimulationRuntimeStore;
        this.capacityManager = capacityManager;
        this.eventProcessor = eventProcessor;
        this.timeService = timeService;
        this.graphService = graphService;
        this.simulationInputService = simulationInputService;
        this.liveSystemScheduler = liveSystemScheduler;
        this.runtimeState = runtimeState;
    }

    SimulationState createMultiSimulationRuntime(
            String simulationId,
            Instant start,
            MultiSimulationBaseline baseline) {
        SimulationState state = new SimulationState(
                simulationId,
                start,
                SimulationStatus.READY,
                timeService.physicalNow(),
                start,
                1.0,
                100.0);
        state.setKind(SimulationKind.MULTI_SIMULATION_RUN);
        state.setLiveInputState(LiveInputState.FROZEN);
        SimulationState previous = runtimeState.simulationCache.putIfAbsent(simulationId, state);
        if (previous != null) {
            throw new IllegalStateException("Simulation runtime already exists: " + simulationId);
        }
        multiSimulationRuntimeStore.register(simulationId);
        try {
            multiSimulationRuntimeStore.get(simulationId).graph().restore(baseline.graph());
            try (var context = DatabaseContextHolder.enterSimulationContext(simulationId);
                    var virtualTime = timeService.enterVirtualTime(start)) {
                historicalGraphBuilder.restoreMultiRunItems(baseline.graph());
                for (DomainEvent configurationEvent : baseline.configurationEvents()) {
                    eventProcessor.processEventWithoutBroadcast(configurationEvent);
                }
                anomalyObservationRepository.initializeForkTimestamp(start);
            }
            return state;
        } catch (RuntimeException failure) {
            destroySimulation(simulationId);
            throw failure;
        }
    }

    SimulationState createSimulation(String simulationId, Instant timestamp) {
        capacityManager.enforceAdmission();
        Instant createdAt = timeService.physicalNow();
        SimulationState state = new SimulationState(simulationId, timestamp, SimulationStatus.QUEUED,
                createdAt, null, 1.0, 0.0);
        runtimeState.simulationCache.put(simulationId, state);
        runtimeState.persistState(state);
        try (var context = DatabaseContextHolder.enterSimulationContext(simulationId)) {
            anomalyObservationRepository.initializeForkTimestamp(createdAt);
        }
        runtimeState.waitingQueue.add(new SimulationRequest(simulationId, timestamp));
        processWaitingQueue();
        return state;
    }

    SimulationState createWhatIf(String sourceSimulationId) {
        capacityManager.enforceAdmission();
        if (!capacityManager.hasBuildingCapacity() || !runtimeState.buildPermits.tryAcquire()) {
            capacityManager.reject("A simulation is already being built");
        }
        try {
            if (sourceSimulationId == null || sourceSimulationId.isBlank()) {
                return eventProcessor.withLiveSnapshotBarrier(() -> forkWhatIf(null));
            }
            synchronized (runtimeState.lifecycleLock(sourceSimulationId)) {
                SimulationState source = simulations.getSimulationState(sourceSimulationId);
                if (source.getKind() != SimulationKind.STANDARD
                        || (source.getStatus() != SimulationStatus.PLAYING && source.getStatus() != SimulationStatus.READY
                        && source.getStatus() != SimulationStatus.PAUSED && source.getStatus() != SimulationStatus.STOPPED)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Source simulation is not ready to fork");
                }
                synchronized (source.getExecutionLock()) {
                    SimulationStatus previousStatus = source.getStatus();
                    double previousSpeedFactor = source.getSpeedFactor();
                    if (previousStatus == SimulationStatus.PLAYING) simulations.pauseSimulation(sourceSimulationId);
                    source.setStatus(SimulationStatus.PAUSED);
                    source.setLastHeartbeatTimestamp(timeService.physicalNow());
                    runtimeState.persistState(source);
                    try {
                        return eventProcessor.withLiveSnapshotBarrier(() -> forkWhatIf(source));
                    } catch (RuntimeException failure) {
                        restoreSourceAfterFailedFork(source, previousStatus, previousSpeedFactor);
                        throw failure;
                    }
                }
            }
        } finally {
            runtimeState.buildPermits.release();
            processWaitingQueue();
        }
    }

    private void restoreSourceAfterFailedFork(
            SimulationState source,
            SimulationStatus previousStatus,
            double previousSpeedFactor) {
        Instant restartTimestamp = simulations.getSimulationClock(source);
        if (previousStatus == SimulationStatus.PLAYING) {
            simulations.startPlaybackWorker(source.getId(), source, restartTimestamp, previousSpeedFactor);
            return;
        }
        source.setStatus(previousStatus);
        source.setSpeedFactor(previousSpeedFactor);
        source.setLastHeartbeatTimestamp(timeService.physicalNow());
        runtimeState.persistState(source);
        webSocketService.broadcastSimulationUpdate(source.getId(), previousStatus, restartTimestamp);
    }

    private SimulationState forkWhatIf(SimulationState source) {
        clickHouseService.flushAllEventsOrThrow();
        Instant handoff = timeService.physicalNow();
        Instant fork = source == null ? handoff : simulations.getSimulationClock(source);
        String id = "sim_" + UUID.randomUUID().toString().replace("-", "");
        SimulationState state = new SimulationState(id, fork, SimulationStatus.BUILDING, handoff, fork, 1.0, 0.0);
        state.setKind(source == null ? SimulationKind.WHAT_IF_LIVE : SimulationKind.WHAT_IF_SIMULATION);
        state.setSourceSimulationId(source == null ? null : source.getId());
        state.setForkTimestamp(fork);
        state.setLiveHandoffTimestamp(handoff);
        runtimeState.simulationCache.put(id, state);
        runtimeState.persistState(state);
        try {
            com.flunav.backend.models.graph.GraphData baseline;
            try (var context = DatabaseContextHolder.enterSimulationContext(source == null ? null : source.getId())) {
                baseline = graphService.getGraphData(fork, false);
            }
            capacityManager.validateSourceItemCount(baseline.getItems().size());
            orientDBService.createInMemoryDatabase(id);
            try (var context = DatabaseContextHolder.enterSimulationContext(id);
                    var virtualTime = timeService.enterVirtualTime(fork)) {
                historicalGraphBuilder.restoreFromSnapshotData(baseline);
                for (DomainEvent mapping : clickHouseService.getLatestConfigurationEventsBefore(fork)) {
                    eventProcessor.processEventWithoutBroadcast(mapping);
                }
                anomalyObservationRepository.initializeForkTimestamp(fork);
                if (source != null) {
                    state.getInternalEventQueue().addAll(source.getInternalEventQueue());
                    state.getScheduledEventsByItem().putAll(source.getScheduledEventsByItem());
                }
                simulations.recalculateMovementSchedules(id, fork);
                if (source == null) {
                    for (var item : liveItemRepository.getAllActiveItems()) {
                        if (item.getType() == PositionType.LOCATION) {
                            DomainEvent scheduled = liveSystemScheduler.getScheduledEvent(item.getId());
                            simulations.addInternalEvent(scheduled != null ? scheduled : new flunav.events.ItemPositionChangedEvent(
                                    item.getId(), item.getPositionId(), 0.0, fork));
                        }
                    }
                }
            }
            simulationInputService.register(id, state.getKind());
            if (fork.isBefore(handoff)) {
                simulationInputService.addHistory(id, clickHouseService.getEventsBetween(fork, handoff));
            }
            if (fork.isAfter(handoff)) simulations.freezeLiveInput(state);
            state.setStatus(SimulationStatus.PAUSED);
            state.setBuildProgress(100.0);
            runtimeState.persistState(state);
            return state;
        } catch (RuntimeException failure) {
            destroySimulation(id);
            throw failure;
        }
    }

    void destroySimulation(String simulationId) {
        simulations.requireNoWhatIfChild(simulationId);
        simulationInputService.unregister(simulationId);
        simulations.cancelPlayback(simulationId);
        runtimeState.waitingQueue.removeIf(request -> request.simulationId().equals(simulationId));
        SimulationState state = runtimeState.simulationCache.remove(simulationId);
        boolean multiRuntime = state != null && state.getKind() == SimulationKind.MULTI_SIMULATION_RUN
                || multiSimulationRuntimeStore.contains(simulationId);
        if (!multiRuntime) {
            try {
                orientDBService.dropDatabase(simulationId);
            } catch (Exception e) {
                logger.warn("Failed to drop simulation database {}: {}", simulationId, e.getMessage());
            }
        }
        if (!multiRuntime) {
            try {
                liveItemRepository.cleanupSimulationData(simulationId);
            } catch (Exception e) {
                logger.warn("Failed to cleanup Redis data for simulation {}: {}", simulationId, e.getMessage());
            }
            try {
                pathCacheRepository.cleanupSimulationData(simulationId);
            } catch (Exception e) {
                logger.warn("Failed to cleanup path cache for simulation {}: {}", simulationId, e.getMessage());
            }
            liveSimulationRepository.deleteState(simulationId);
        }
        throughputBucketService.cleanupSimulationHistory(simulationId);
        if (!multiRuntime) {
            try {
                clickHouseService.deleteOperationalAnalyticsForSimulation(simulationId);
            } catch (Exception e) {
                logger.warn("Failed to cleanup ClickHouse analytics for simulation {}: {}", simulationId, e.getMessage());
            }
        }
        routingCoordinator.cleanupSimulation(simulationId);
        if (!multiRuntime) anomalyObservationRepository.cleanupSimulationData(simulationId);
        multiSimulationRuntimeStore.remove(simulationId);

        if (state != null) {
            logger.info("Successfully destroyed simulation: {}", simulationId);
        } else {
            logger.info("Destroyed external or stale simulation resources for simulation: {}", simulationId);
        }
    }

    void processWaitingQueue() {
        if (!runtimeState.waitingQueue.isEmpty() && capacityManager.hasBuildingCapacity() && runtimeState.buildPermits.tryAcquire()) {
            SimulationRequest request = runtimeState.waitingQueue.poll();
            if (request != null) {
                simulations.updateSimulationStatus(request.simulationId(), SimulationStatus.BUILDING, request.timestamp());
                orientDBService.createInMemoryDatabase(request.simulationId());
                historicalGraphBuilder.build(request.simulationId(), request.timestamp(), runtimeState.buildPermits);
            } else {
                runtimeState.buildPermits.release();
            }
        }
    }

    void cleanupAbandonedSimulations() {
        logger.info("Running cleanup job for abandoned simulations...");
        if ("true".equals(System.getProperty("disable-sim-cleanup")))
            return;
        Instant now = timeService.physicalNow();
        for (var metadata : liveSimulationRepository.getAllSimulationStates()) {
            boolean activeMultiSimulationRuntime = metadata.kind() == SimulationKind.MULTI_SIMULATION_RUN
                    && runtimeState.simulationCache.containsKey(metadata.simulationId());
            if (!activeMultiSimulationRuntime
                    && metadata.lastHeartbeatTimestamp() != null
                    && Duration.between(metadata.lastHeartbeatTimestamp(), now).toMinutes() > 2
                    && !simulations.hasWhatIfChild(metadata.simulationId())) {
                destroySimulation(metadata.simulationId());
            }
        }
    }
}
