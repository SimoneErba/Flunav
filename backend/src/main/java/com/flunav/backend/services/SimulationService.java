package com.flunav.backend.services;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.flunav.backend.context.DatabaseContextHolder;
import com.flunav.backend.models.simulation.SimulationState;
import com.flunav.backend.models.simulation.SimulationStatus;
import com.flunav.backend.models.simulation.SimulationKind;
import com.flunav.backend.models.simulation.LiveInputState;
import com.flunav.backend.repositories.LiveItemRepository;
import com.flunav.backend.repositories.PathCacheRepository;
import com.flunav.backend.repositories.LiveSimulationRepository;
import com.flunav.backend.repositories.LiveSimulationRepository.SimulationMetadata;
import com.flunav.backend.repositories.AnomalyObservationRepository;
import flunav.events.AnomalyEvaluationTickEvent;

import flunav.events.DomainEvent;
import flunav.types.PositionType;

/**
 * Owns simulation lifecycle, virtual clock state, and projected internal events.
 *
 * Redis holds recoverable lifecycle metadata, while the in-process
 * {@link SimulationState} also owns queues, locks, and playback generations that
 * cannot be reconstructed by a concurrent Redis read. Every operation that touches
 * simulated topology or hot state must re-enter {@link DatabaseContextHolder}.
 * Historical reconstruction itself is delegated to {@link HistoricalGraphBuilder}
 * and timed playback to {@link HistoricalEventPlayer}.
 */
@Service
public class SimulationService {

    private static final Logger logger = LoggerFactory.getLogger(SimulationService.class);

    private final OrientDBService orientDBService;
    private final HistoricalEventPlayer historicalEventPlayer;
    private final HistoricalGraphBuilder historicalGraphBuilder;
    private final WebSocketService webSocketService;
    private final LiveItemRepository liveItemRepository;
    private final LiveSimulationRepository liveSimulationRepository;
    private final ThroughputBucketService throughputBucketService;
    private final PathCacheRepository pathCacheRepository;
    private final TopologyProvider topologyProvider;
    private final ItemMovementProcessor itemMovementProcessor;
    private final ClickHouseService clickHouseService;
    private final RoutingCoordinator routingCoordinator;
    private final AnomalyEngine anomalyEngine;
    private final AnomalyObservationRepository anomalyObservationRepository;
    private final int maxActiveSimulations;
    private final int maxBuildingSimulations;
    private final long maxActiveItemsPerSimulation;
    private final long minFreeMemoryBytes;

    @org.springframework.beans.factory.annotation.Autowired
    @Lazy
    private EventProcessor eventProcessor;

    @org.springframework.beans.factory.annotation.Autowired
    private TimeService timeService;

    private final GraphService graphService;
    private final SimulationInputService simulationInputService;
    private final LiveSystemScheduler liveSystemScheduler;

    private final Map<String, SimulationState> simulationCache = new ConcurrentHashMap<>();
    private final Map<String, Future<?>> activePlaybacks = new ConcurrentHashMap<>();
    private final Map<String, PlaybackCancellationReason> playbackCancellationReasons = new ConcurrentHashMap<>();
    private final Semaphore buildPermits;
    private final Queue<SimulationRequest> waitingQueue = new ConcurrentLinkedQueue<>();
    private final Object[] lifecycleLocks = new Object[256];

    public SimulationService(OrientDBService orientDBService, HistoricalEventPlayer historicalEventPlayer,
            WebSocketService webSocketService,
            @Lazy HistoricalGraphBuilder historicalGraphBuilder,
            LiveItemRepository liveItemRepository,
            LiveSimulationRepository liveSimulationRepository,
            @Lazy ThroughputBucketService throughputBucketService,
            PathCacheRepository pathCacheRepository,
            @org.springframework.context.annotation.Lazy TopologyProvider topologyProvider,
            @Lazy ItemMovementProcessor itemMovementProcessor,
            ClickHouseService clickHouseService,
            RoutingCoordinator routingCoordinator,
            @Lazy AnomalyEngine anomalyEngine,
            AnomalyObservationRepository anomalyObservationRepository,
            @Lazy GraphService graphService,
            SimulationInputService simulationInputService,
            @Lazy LiveSystemScheduler liveSystemScheduler,
            @Value("${simulation.capacity.max-active:3}") int maxActiveSimulations,
            @Value("${simulation.capacity.max-building:1}") int maxBuildingSimulations,
            @Value("${simulation.capacity.max-active-items:10000}") long maxActiveItemsPerSimulation,
            @Value("${simulation.capacity.min-free-memory-bytes:536870912}") long minFreeMemoryBytes) {
        this.orientDBService = orientDBService;
        this.historicalEventPlayer = historicalEventPlayer;
        this.historicalGraphBuilder = historicalGraphBuilder;
        this.webSocketService = webSocketService;
        this.liveItemRepository = liveItemRepository;
        this.liveSimulationRepository = liveSimulationRepository;
        this.throughputBucketService = throughputBucketService;
        this.pathCacheRepository = pathCacheRepository;
        this.topologyProvider = topologyProvider;
        this.itemMovementProcessor = itemMovementProcessor;
        this.clickHouseService = clickHouseService;
        this.routingCoordinator = routingCoordinator;
        this.anomalyEngine = anomalyEngine;
        this.anomalyObservationRepository = anomalyObservationRepository;
        this.graphService = graphService;
        this.simulationInputService = simulationInputService;
        this.liveSystemScheduler = liveSystemScheduler;
        this.maxActiveSimulations = maxActiveSimulations;
        this.maxBuildingSimulations = maxBuildingSimulations;
        this.maxActiveItemsPerSimulation = maxActiveItemsPerSimulation;
        this.minFreeMemoryBytes = minFreeMemoryBytes;
        this.buildPermits = new Semaphore(maxBuildingSimulations > 0 ? maxBuildingSimulations : Integer.MAX_VALUE);
        for (int index = 0; index < lifecycleLocks.length; index++) {
            lifecycleLocks[index] = new Object();
        }
    }

    public SimulationState createSimulation(Instant timestamp) {
        String simulationId = "sim_" + UUID.randomUUID().toString().replace("-", "");
        return createSimulation(simulationId, timestamp);
    }

    /**
     * Persists and queues a simulation build before async work starts.
     * Keeping state in Redis as well as memory lets controllers and workers recover
     * progress even when they observe different service instances or threads.
     */
    public synchronized SimulationState createSimulation(String simulationId, Instant timestamp) {
        enforceSimulationCapacity();
        Instant createdAt = timeService.physicalNow();
        SimulationState state = new SimulationState(simulationId, timestamp, SimulationStatus.QUEUED,
                createdAt, null, 1.0, 0.0);
        simulationCache.put(simulationId, state);
        persistState(state);
        try (var context = DatabaseContextHolder.enterSimulationContext(simulationId)) {
            anomalyObservationRepository.initializeForkTimestamp(createdAt);
        }
        waitingQueue.add(new SimulationRequest(simulationId, timestamp));
        processWaitingQueue();
        return state;
    }

    /**
     * Forks live state or a paused historical simulation into an isolated what-if
     * branch. The source snapshot and live-input registration share the event
     * processor barrier so no committed live event can fall into a handoff gap.
     */
    public synchronized SimulationState createWhatIf(String sourceSimulationId) {
        enforceSimulationCapacity();
        if (!hasBuildingCapacity() || !buildPermits.tryAcquire()) {
            rejectCapacity("A simulation is already being built");
        }
        try {
            if (sourceSimulationId == null || sourceSimulationId.isBlank()) {
                return eventProcessor.withLiveSnapshotBarrier(() -> forkWhatIf(null));
            }
            synchronized (lifecycleLock(sourceSimulationId)) {
                SimulationState source = getSimulationState(sourceSimulationId);
                if (source.getKind() != SimulationKind.STANDARD
                        || (source.getStatus() != SimulationStatus.PLAYING && source.getStatus() != SimulationStatus.READY
                        && source.getStatus() != SimulationStatus.PAUSED && source.getStatus() != SimulationStatus.STOPPED)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Source simulation is not ready to fork");
                }
                synchronized (source.getExecutionLock()) {
                    SimulationStatus previousStatus = source.getStatus();
                    double previousSpeedFactor = source.getSpeedFactor();
                    if (previousStatus == SimulationStatus.PLAYING) pauseSimulation(sourceSimulationId);
                    source.setStatus(SimulationStatus.PAUSED);
                    source.setLastHeartbeatTimestamp(timeService.physicalNow());
                    persistState(source);
                    try {
                        return eventProcessor.withLiveSnapshotBarrier(() -> forkWhatIf(source));
                    } catch (RuntimeException failure) {
                        restoreSourceAfterFailedFork(source, previousStatus, previousSpeedFactor);
                        throw failure;
                    }
                }
            }
        } finally {
            buildPermits.release();
            processWaitingQueue();
        }
    }

    /** Restores the source lifecycle when construction of its child branch fails. */
    private void restoreSourceAfterFailedFork(
            SimulationState source,
            SimulationStatus previousStatus,
            double previousSpeedFactor) {
        Instant restartTimestamp = getSimulationClock(source);
        if (previousStatus == SimulationStatus.PLAYING) {
            startPlaybackWorker(source.getId(), source, restartTimestamp, previousSpeedFactor);
            return;
        }
        source.setStatus(previousStatus);
        source.setSpeedFactor(previousSpeedFactor);
        source.setLastHeartbeatTimestamp(timeService.physicalNow());
        persistState(source);
        webSocketService.broadcastSimulationUpdate(source.getId(), previousStatus, restartTimestamp);
    }

    private SimulationState forkWhatIf(SimulationState source) {
        clickHouseService.flushAllEventsOrThrow();
        Instant handoff = timeService.physicalNow();
        Instant fork = source == null ? handoff : getSimulationClock(source);
        String id = "sim_" + UUID.randomUUID().toString().replace("-", "");
        SimulationState state = new SimulationState(id, fork, SimulationStatus.BUILDING, handoff, fork, 1.0, 0.0);
        state.setKind(source == null ? SimulationKind.WHAT_IF_LIVE : SimulationKind.WHAT_IF_SIMULATION);
        state.setSourceSimulationId(source == null ? null : source.getId());
        state.setForkTimestamp(fork);
        state.setLiveHandoffTimestamp(handoff);
        simulationCache.put(id, state);
        persistState(state);
        try {
            com.flunav.backend.models.graph.GraphData baseline;
            try (var context = DatabaseContextHolder.enterSimulationContext(source == null ? null : source.getId())) {
                baseline = graphService.getGraphData(fork, false);
            }
            if (maxActiveItemsPerSimulation > 0 && baseline.getItems().size() > maxActiveItemsPerSimulation) {
                rejectCapacity("Source item count exceeds simulation limit " + maxActiveItemsPerSimulation);
            }
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
                recalculateMovementSchedules(id, fork);
                if (source == null) {
                    for (var item : liveItemRepository.getAllActiveItems()) {
                        if (item.getType() == PositionType.LOCATION) {
                            DomainEvent scheduled = liveSystemScheduler.getScheduledEvent(item.getId());
                            addInternalEvent(scheduled != null ? scheduled : new flunav.events.ItemPositionChangedEvent(
                                    item.getId(), item.getPositionId(), 0.0, fork));
                        }
                    }
                }
            }
            simulationInputService.register(id, state.getKind());
            if (fork.isBefore(handoff)) {
                simulationInputService.addHistory(id, clickHouseService.getEventsBetween(fork, handoff));
            }
            if (fork.isAfter(handoff)) freezeLiveInput(state);
            state.setStatus(SimulationStatus.PAUSED);
            state.setBuildProgress(100.0);
            persistState(state);
            return state;
        } catch (RuntimeException failure) {
            destroySimulation(id);
            throw failure;
        }
    }

    /**
     * Registers a simulation for direct live intake after flushing persisted
     * history. Registration happens under the live snapshot barrier so later
     * asynchronous ClickHouse writes cannot create an input gap.
     */
    public void ensureLiveHandoff(SimulationState state) {
        if (simulationInputService.isRegistered(state.getId()) || state.getLiveInputState() == LiveInputState.FROZEN) return;
        eventProcessor.withLiveSnapshotBarrier(() -> {
            clickHouseService.flushAllEventsOrThrow();
            state.setLiveHandoffTimestamp(timeService.physicalNow());
            simulationInputService.register(state.getId(), state.getKind());
            persistState(state);
            return null;
        });
    }

    /**
     * Permanently freezes live input when virtual time crosses physical now. The
     * cutoff survives pause, resume, and playback-speed changes.
     */
    public void freezeLiveInputIfFuture(String simulationId, Instant timestamp) {
        SimulationState state = getSimulationState(simulationId);
        if (timestamp.isAfter(timeService.physicalNow())) freezeLiveInput(state);
    }

    private void freezeLiveInput(SimulationState state) {
        if (state.getLiveInputState() == LiveInputState.FROZEN) return;
        state.setLiveInputState(LiveInputState.FROZEN);
        simulationInputService.unregister(state.getId());
        persistState(state);
        webSocketService.broadcastSimulationMode(state);
    }

    /** Detaches an isolated scenario from live intake while retaining its simulation lifecycle. */
    public void isolateFromLiveInput(String simulationId) {
        synchronized (lifecycleLock(simulationId)) {
            freezeLiveInput(getSimulationState(simulationId));
        }
    }

    public SimulationState getOrCreateSimulation(String simulationId, Instant timestamp) {
        SimulationState cached = simulationCache.get(simulationId);
        if (cached != null) {
            return cached;
        }
        if (liveSimulationRepository.exists(simulationId)) {
            return getSimulationState(simulationId);
        }
        return createSimulation(simulationId, timestamp);
    }

    /**
     * Starts playback from the last processed simulation timestamp.
     * Playback resumes from stored virtual time so pause, stop, and historical
     * rebuilds do not restart from the original restore point.
     */
    public void startPlayback(String simulationId, double speedFactor) {
        synchronized (lifecycleLock(simulationId)) {
            requireNoWhatIfChild(simulationId);
            validateSpeedFactor(speedFactor);
            SimulationState state = getSimulationState(simulationId);
            if (state.getStatus() != SimulationStatus.READY && state.getStatus() != SimulationStatus.STOPPED
                    && state.getStatus() != SimulationStatus.PAUSED) {
                throw new IllegalStateException("Simulation not ready for playback. Current status: " + state.getStatus());
            }
            Instant simulationStartTime = state.getLastProcessedTimestamp() != null ? state.getLastProcessedTimestamp()
                    : state.getTimestamp();
            startPlaybackWorker(simulationId, state, simulationStartTime, speedFactor);
        }
    }

    private void startPlaybackWorker(String simulationId, SimulationState state, Instant simulationStartTime,
            double speedFactor) {
        initializeAnomalySchedule(simulationId, simulationStartTime);
        state.setStatus(SimulationStatus.PLAYING);
        state.setSpeedFactor(speedFactor);
        state.setLastProcessedTimestamp(simulationStartTime);
        persistState(state);
        webSocketService.broadcastSimulationUpdate(simulationId, SimulationStatus.PLAYING, simulationStartTime);
        long generation = state.getPlaybackGeneration().incrementAndGet();
        var playbackFuture = historicalEventPlayer.playEvents(simulationId, simulationStartTime, speedFactor, generation);
        activePlaybacks.put(simulationId, playbackFuture);
    }

    public void cancelPlayback(String simulationId) {
        synchronized (lifecycleLock(simulationId)) {
            Future<?> playbackFuture = activePlaybacks.get(simulationId);
            if (playbackFuture == null || playbackFuture.isDone()) {
                activePlaybacks.remove(simulationId, playbackFuture);
                playbackCancellationReasons.remove(simulationId);
                return;
            }

            SimulationState state = simulationCache.get(simulationId);
            if (state != null) {
                synchronized (state.getExecutionLock()) {
                    state.setStatus(SimulationStatus.STOPPED);
                    state.getPlaybackGeneration().incrementAndGet();
                    persistState(state);
                }
            }
            cancelActivePlayback(simulationId, PlaybackCancellationReason.STOP);
        }
    }

    public void pauseSimulation(String simulationId) {
        synchronized (lifecycleLock(simulationId)) {
            SimulationState state = simulationCache.get(simulationId);
            Future<?> playbackTask = activePlaybacks.get(simulationId);
            if (state == null || playbackTask == null) {
                throw new IllegalStateException("Simulation " + simulationId + " does not exist or is not running.");
            }
            if (state.getStatus() != SimulationStatus.PLAYING) {
                throw new IllegalStateException(
                        "Simulation " + simulationId + " is not playing. Current state: " + state.getStatus());
            }
            synchronized (state.getExecutionLock()) {
                state.setStatus(SimulationStatus.PAUSED);
                state.getPlaybackGeneration().incrementAndGet();
                persistState(state);
                webSocketService.broadcastSimulationUpdate(simulationId, SimulationStatus.PAUSED,
                        state.getLastProcessedTimestamp());
                cancelActivePlayback(simulationId, PlaybackCancellationReason.PAUSE);
            }
        }
    }

    /**
     * Changes playback speed without changing event timestamps.
     * Active playback is cancelled, movement physics are checkpointed at the same
     * virtual instant, and scheduling is rebuilt with the new wall-clock delay.
     */
    public void updatePlaybackSpeed(String simulationId, double newSpeedFactor) {
        synchronized (lifecycleLock(simulationId)) {
            validateSpeedFactor(newSpeedFactor);
            SimulationState state = getSimulationState(simulationId);
            state.setSpeedFactor(newSpeedFactor);
            persistState(state);
            webSocketService.broadcastSpeedUpdate(simulationId, newSpeedFactor, getSimulationClock(state));

            if (state.getStatus() != SimulationStatus.PLAYING) {
                return;
            }

            synchronized (state.getExecutionLock()) {
                Instant restartTimestamp = getSimulationClock(state);
                state.getPlaybackGeneration().incrementAndGet();
                cancelActivePlayback(simulationId, PlaybackCancellationReason.RESCHEDULE);
                state.setStatus(SimulationStatus.PLAYING);
                state.setLastProcessedTimestamp(restartTimestamp);
                persistState(state);
                recalculateMovementSchedules(simulationId, restartTimestamp);
                startPlaybackWorker(simulationId, state, restartTimestamp, newSpeedFactor);
            }
        }
    }

    public boolean consumePlaybackRescheduleInterruption(String simulationId) {
        return playbackCancellationReasons.remove(simulationId) == PlaybackCancellationReason.RESCHEDULE;
    }

    /**
     * Destroys all isolated state for a simulation.
     * Both the in-memory OrientDB database and Redis namespace must be removed so a
     * later simulation id cannot inherit stale topology or hot item state.
     */
    public void destroySimulation(String simulationId) {
        requireNoWhatIfChild(simulationId);
        simulationInputService.unregister(simulationId);
        cancelPlayback(simulationId);
        waitingQueue.removeIf(request -> request.simulationId().equals(simulationId));
        SimulationState state = simulationCache.remove(simulationId);
        try {
            orientDBService.dropDatabase(simulationId);
        } catch (Exception e) {
            logger.warn("Failed to drop simulation database {}: {}", simulationId, e.getMessage());
        }
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
        throughputBucketService.cleanupSimulationHistory(simulationId);
        try {
            clickHouseService.deleteOperationalAnalyticsForSimulation(simulationId);
        } catch (Exception e) {
            logger.warn("Failed to cleanup ClickHouse analytics for simulation {}: {}", simulationId, e.getMessage());
        }
        routingCoordinator.cleanupSimulation(simulationId);
        anomalyObservationRepository.cleanupSimulationData(simulationId);

        if (state != null) {
            logger.info("Successfully destroyed simulation: {}", simulationId);
        } else {
            logger.info("Destroyed external or stale simulation resources for simulation: {}", simulationId);
        }
    }

    public SimulationState getSimulationState(String simulationId) {
        SimulationState state = loadOrRefreshSimulationState(simulationId);
        if (state == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Simulation not found: " + simulationId);
        }
        return state;
    }

    public void updateHeartbeat(String simulationId) {
        Instant now = timeService.physicalNow();
        SimulationState state = loadOrRefreshSimulationState(simulationId);
        if (state != null) {
            state.setLastHeartbeatTimestamp(now);
            persistState(state);
            if (state.getSourceSimulationId() != null) updateHeartbeat(state.getSourceSimulationId());
            return;
        }

        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Simulation not found: " + simulationId);
    }

    public void updateSimulationStatus(String simulationId, SimulationStatus status, Instant timestamp) {
        SimulationState state = loadOrRefreshSimulationState(simulationId);
        if (state != null) {
            state.setStatus(status);
            if (timestamp != null) {
                state.setLastProcessedTimestamp(timestamp);
            }
            if (status == SimulationStatus.READY) {
                state.setBuildProgress(100.0);
            }
            persistState(state);
            Instant updateTimestamp = timestamp != null ? timestamp : timeService.physicalNow();
            this.webSocketService.broadcastSimulationUpdate(simulationId, state.getStatus(), updateTimestamp,
                    state.getBuildProgress());
        }
    }

    /** Advances a paused scenario clock without overwriting an active playback status. */
    public void updateSimulationProgress(String simulationId, Instant timestamp) {
        SimulationState state = loadOrRefreshSimulationState(simulationId);
        if (state == null || timestamp == null) {
            return;
        }
        synchronized (state.getExecutionLock()) {
            if (state.getStatus() != SimulationStatus.PLAYING) {
                Instant previous = state.getLastProcessedTimestamp();
                if (previous == null || timestamp.isAfter(previous)) {
                    state.setLastProcessedTimestamp(timestamp);
                }
                persistState(state);
                webSocketService.broadcastSimulationUpdate(simulationId, state.getStatus(),
                        state.getLastProcessedTimestamp(), state.getBuildProgress());
            }
        }
    }

    public void updateBuildProgress(String simulationId, double progress, Instant processedTimestamp) {
        SimulationState state = loadOrRefreshSimulationState(simulationId);
        if (state == null) {
            return;
        }

        double boundedProgress = Math.max(0.0, Math.min(100.0, progress));
        state.setBuildProgress(Math.max(state.getBuildProgress(), boundedProgress));
        persistState(state);
        Instant updateTimestamp = processedTimestamp != null ? processedTimestamp : state.getTimestamp();
        webSocketService.broadcastSimulationUpdate(simulationId, state.getStatus(), updateTimestamp,
                state.getBuildProgress());
    }

    /**
     * Starts queued simulation builds while respecting the build semaphore.
     * The permit is passed to the async builder so completion and failure release
     * capacity from the worker that actually owns the build lifecycle.
     */
    public void processWaitingQueue() {
        if (!waitingQueue.isEmpty() && hasBuildingCapacity() && buildPermits.tryAcquire()) {
            SimulationRequest request = waitingQueue.poll();
            if (request != null) {
                updateSimulationStatus(request.simulationId(), SimulationStatus.BUILDING, request.timestamp());
                orientDBService.createInMemoryDatabase(request.simulationId());
                historicalGraphBuilder.build(request.simulationId(), request.timestamp(), buildPermits);
            } else {
                buildPermits.release();
            }
        }
    }

    @Scheduled(fixedRate = 300_000)
    public void cleanupAbandonedSimulations() {
        logger.info("Running cleanup job for abandoned simulations...");
        if ("true".equals(System.getProperty("disable-sim-cleanup")))
            return;
        Instant now = timeService.physicalNow();
        for (var heartbeat : liveSimulationRepository.getAllSimulationHeartbeats()) {
            if (Duration.between(heartbeat.lastHeartbeatTimestamp(), now).toMinutes() > 2
                    && !hasWhatIfChild(heartbeat.simulationId())) {
                destroySimulation(heartbeat.simulationId());
            }
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void cleanupAbandonedSimulationsOnStartup() {
        cleanupAbandonedSimulations();
    }

    public SimulationState getCurrentSimulation() {
        var id = DatabaseContextHolder.getSimulationId();
        var state = id != null ? loadOrRefreshSimulationState(id) : null;
        if (id != null) {
            logger.debug("getCurrentSimulation for id {}: found state? {}", id, (state != null));
        }
        return state;
    }

    private boolean hasWhatIfChild(String simulationId) {
        return simulationCache.values().stream().anyMatch(state -> simulationId.equals(state.getSourceSimulationId()));
    }

    private void requireNoWhatIfChild(String simulationId) {
        if (hasWhatIfChild(simulationId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Exit the child what-if scenario first");
        }
    }

    public String getCurrentSimulationId() {
        return DatabaseContextHolder.getSimulationId();
    }

    public Instant getSimulationClock(SimulationState state) {
        if (state == null) {
            return timeService.physicalNow();
        }
        return state.getLastProcessedTimestamp() != null ? state.getLastProcessedTimestamp() : state.getTimestamp();
    }

    public Instant getSimulationClock(String simulationId) {
        return getSimulationClock(getSimulationState(simulationId));
    }

    /**
     * Stores the next projected internal event for a simulation item.
     * Replacing an existing same-entity event prevents stale movement projections
     * from firing after a newer checkpoint or route decision changed the schedule.
     */
    public void addInternalEvent(flunav.events.DomainEvent event) {
        SimulationState state = getCurrentSimulation();
        if (state != null && event instanceof flunav.events.EntityEvent ee) {
            // An item has one projected transition. A newer projection replaces the
            // previous event regardless of its concrete movement-event type.
            state.getInternalEventQueue().removeIf(
                    e -> e instanceof flunav.events.EntityEvent e2 && e2.getEntityId().equals(ee.getEntityId()));
            state.getInternalEventQueue().add(event);
            state.getScheduledEventsByItem().put(ee.getEntityId(), event);
        }
    }

    public void cancelInternalEvent(String itemId) {
        SimulationState state = getCurrentSimulation();
        if (state != null) {
            state.getInternalEventQueue()
                    .removeIf(e -> e instanceof flunav.events.EntityEvent ee && ee.getEntityId().equals(itemId));
            state.getScheduledEventsByItem().remove(itemId);
        }
    }

    public void markInternalEventProcessed(flunav.events.DomainEvent event) {
        SimulationState state = getCurrentSimulation();
        if (state != null && event instanceof flunav.events.EntityEvent ee) {
            state.getScheduledEventsByItem().remove(ee.getEntityId(), event);
        }
    }

    public DomainEvent getScheduledEvent(String itemId) {
        SimulationState state = getCurrentSimulation();
        if (state != null) {
            return state.getScheduledEventsByItem().get(itemId);
        }
        return null;
    }

    /**
     * Projects queued internal simulation events up to a future target time.
     * The final checkpoint keeps in-flight item physics aligned even when no
     * internal event lands exactly on the requested timestamp.
     */
    public void processEventsUntil(String simulationId, Instant targetTime) {
        SimulationState state = loadOrRefreshSimulationState(simulationId);
        if (state == null)
            return;

        while (true) {
            var event = state.getInternalEventQueue().peek();
            if (event == null || event.getTimestamp().isAfter(targetTime)) {
                break;
            }

            processNextInternalEvent(simulationId);
        }

        checkpointSimulationAt(simulationId, targetTime);
    }

    /**
     * Processes one due internal event from the simulation queue.
     * Pulling from the queue here centralizes timestamp checkpointing and context
     * re-entry for both historical builds and interactive playback.
     */
    public DomainEvent processNextInternalEvent(String simulationId) {
        SimulationState state = loadOrRefreshSimulationState(simulationId);
        if (state == null) {
            return null;
        }

        DomainEvent event = state.getInternalEventQueue().poll();
        if (event == null) {
            return null;
        }

        processInternalEvent(simulationId, state, event);
        return event;
    }

    /**
     * Advances all moving items to a virtual timestamp without firing a new event.
     * This preserves accumulated conveyor distance for future projections and for
     * graph reads that happen between scheduled internal events.
     */
    public void checkpointSimulationAt(String simulationId, Instant targetTime) {
        SimulationState state = loadOrRefreshSimulationState(simulationId);
        if (state == null) {
            return;
        }

        if (state.getStatus() != SimulationStatus.BUILDING && state.getStatus() != SimulationStatus.QUEUED) {
            freezeLiveInputIfFuture(simulationId, targetTime);
        }
        checkpointAllItems(simulationId, targetTime);
        state.setLastProcessedTimestamp(targetTime);
        persistState(state);
    }

    /**
     * Applies a projected internal event inside the simulation and virtual-time
     * contexts. This keeps replay deterministic and prevents projected events from
     * being persisted or broadcast as live history.
     */
    private void processInternalEvent(String simulationId, SimulationState state, DomainEvent event) {
        checkpointAllItems(simulationId, event.getTimestamp());
        state.setLastProcessedTimestamp(event.getTimestamp());
        persistState(state);

        if (event instanceof flunav.events.EntityEvent ee) {
            state.getScheduledEventsByItem().remove(ee.getEntityId(), event);
        }

        try (var ctx = DatabaseContextHolder.enterSimulationContext(simulationId);
                var timeContext = timeService.enterVirtualTime(event.getTimestamp())) {
            eventProcessor.processEventWithoutBroadcast(event);
        }
    }

    public void updateLastProcessedTimestamp(String simulationId, Instant timestamp) {
        SimulationState state = loadOrRefreshSimulationState(simulationId);
        if (state != null) {
            state.setLastProcessedTimestamp(timestamp);
            persistState(state);
        }
    }

    private SimulationState loadOrRefreshSimulationState(String simulationId) {
        SimulationState cached = simulationCache.get(simulationId);
        // Redis contains lifecycle metadata, not the in-memory event queue. Once this
        // process owns a state, refreshing it would discard work or rewind its clock.
        if (cached != null) {
            return cached;
        }
        SimulationMetadata metadata = liveSimulationRepository.getState(simulationId).orElse(null);

        if (metadata == null) {
            return cached;
        }

        SimulationState restored = new SimulationState(metadata.simulationId(), metadata.timestamp(), metadata.status(),
                metadata.lastHeartbeatTimestamp(), metadata.lastProcessedTimestamp(), metadata.speedFactor(),
                metadata.buildProgress());
        restoreBranchMetadata(restored, metadata);
        SimulationState existing = simulationCache.putIfAbsent(simulationId, restored);
        return existing != null ? existing : restored;
    }

    private void persistState(SimulationState state) {
        liveSimulationRepository.saveState(new SimulationMetadata(
                state.getId(),
                state.getTimestamp(),
                state.getStatus(),
                state.getLastHeartbeatTimestamp(),
                state.getLastProcessedTimestamp(),
                state.getSpeedFactor(),
                state.getBuildProgress(), state.getKind(), state.getSourceSimulationId(), state.getForkTimestamp(),
                state.getLiveHandoffTimestamp(), state.getLiveInputState()));
    }

    private void restoreBranchMetadata(SimulationState state, SimulationMetadata metadata) {
        state.setKind(metadata.kind());
        state.setSourceSimulationId(metadata.sourceSimulationId());
        state.setForkTimestamp(metadata.forkTimestamp());
        state.setLiveHandoffTimestamp(metadata.liveHandoffTimestamp());
        state.setLiveInputState(metadata.liveInputState());
    }

    /**
     * Seeds each cadence at its first unprocessed UTC-aligned boundary. Tick state
     * is read inside the simulation namespace so restarts do not duplicate a
     * boundary that was already committed.
     */
    public void initializeAnomalySchedule(String simulationId, Instant startingTimestamp) {
        SimulationState state = loadOrRefreshSimulationState(simulationId);
        if (state == null) {
            return;
        }
        try (var context = DatabaseContextHolder.enterSimulationContext(simulationId)) {
            anomalyObservationRepository.initializeForkTimestamp(timeService.physicalNow());
            for (AnomalyEvaluationTickEvent.Cadence cadence : AnomalyEvaluationTickEvent.Cadence.values()) {
                boolean queued = state.getInternalEventQueue().stream()
                        .anyMatch(event -> event instanceof AnomalyEvaluationTickEvent tick
                                && tick.getCadence() == cadence);
                if (queued) {
                    continue;
                }
                Instant last = anomalyObservationRepository.getLastBoundary(cadence);
                Instant after = last != null ? last : startingTimestamp;
                state.getInternalEventQueue().add(new AnomalyEvaluationTickEvent(cadence,
                        anomalyEngine.nextBoundary(after, cadence)));
            }
        }
    }

    public void scheduleNextAnomalyTick(AnomalyEvaluationTickEvent processed) {
        SimulationState state = getCurrentSimulation();
        if (state == null) {
            return;
        }
        boolean alreadyQueued = state.getInternalEventQueue().stream()
                .anyMatch(event -> event instanceof AnomalyEvaluationTickEvent tick
                        && tick.getCadence() == processed.getCadence()
                        && tick.getTimestamp().isAfter(processed.getTimestamp()));
        if (!alreadyQueued) {
            state.getInternalEventQueue().add(new AnomalyEvaluationTickEvent(processed.getCadence(),
                    anomalyEngine.nextBoundary(processed.getTimestamp(), processed.getCadence())));
        }
    }

    /**
     * Rejects speed factors that would break playback timing calculations.
     */
    private void validateSpeedFactor(double speedFactor) {
        if (!Double.isFinite(speedFactor) || speedFactor <= 0) {
            throw new IllegalArgumentException("Playback speed factor must be greater than zero.");
        }
    }

    /**
     * Rejects new simulations before expensive in-memory graph state is allocated.
     * These limits protect the shared backend, OrientDB, Redis, and ClickHouse
     * containers from unbounded concurrent simulation growth.
     */
    private void enforceSimulationCapacity() {
        SimulationCounts counts = countSimulations();
        if (maxActiveSimulations > 0 && counts.active() >= maxActiveSimulations) {
            rejectCapacity("Maximum active simulations reached: " + maxActiveSimulations);
        }
        long liveActiveItems = liveItemRepository.countActiveItems(null);
        if (maxActiveItemsPerSimulation > 0 && liveActiveItems > maxActiveItemsPerSimulation) {
            rejectCapacity("Active item count " + liveActiveItems + " exceeds simulation limit "
                    + maxActiveItemsPerSimulation);
        }

        if (minFreeMemoryBytes > 0 && availableHeapBytes() < minFreeMemoryBytes) {
            rejectCapacity("Backend free heap is below the simulation admission threshold");
        }
    }

    private long availableHeapBytes() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory();
    }

    private boolean hasBuildingCapacity() {
        return hasBuildingCapacity(countSimulations());
    }

    private boolean hasBuildingCapacity(SimulationCounts counts) {
        return maxBuildingSimulations <= 0 || counts.building() < maxBuildingSimulations;
    }

    private SimulationCounts countSimulations() {
        int active = 0;
        int building = 0;
        for (var metadata : liveSimulationRepository.getAllSimulationStates()) {
            SimulationStatus status = metadata.status();
            if (status == SimulationStatus.BUILDING) {
                building++;
            }
            if (status != SimulationStatus.FAILED) {
                active++;
            }
        }
        return new SimulationCounts(active, building);
    }

    private void rejectCapacity(String reason) {
        throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, reason);
    }

    private void checkpointAllItems(String simulationId, Instant now) {
        try (var ctx = DatabaseContextHolder.enterSimulationContext(simulationId)) {
            checkpointAllItemsInCurrentContext(now);
        }
    }

    /**
     * Rebuilds movement schedules after a speed-factor change.
     * Items are checkpointed first so accumulation recalculation starts from the
     * current virtual instant rather than from stale entry timestamps.
     */
    private void recalculateMovementSchedules(String simulationId, Instant restartTimestamp) {
        try (var ctx = DatabaseContextHolder.enterSimulationContext(simulationId);
                var timeContext = timeService.enterVirtualTime(restartTimestamp)) {
            checkpointAllItemsInCurrentContext(restartTimestamp);

            Set<String> conveyorIds = new HashSet<>();
            for (var itemData : liveItemRepository.getAllActiveItems()) {
                if (itemData.getType() == PositionType.CONVEYOR && itemData.getPositionId() != null) {
                    conveyorIds.add(itemData.getPositionId());
                }
            }

            for (String conveyorId : conveyorIds) {
                itemMovementProcessor.recalculateConveyorAccumulation(conveyorId);
            }
        }
    }

    /**
     * Converts elapsed virtual time into stored conveyor distance for every moving
     * item. Simulation replay relies on this derived state because Redis stores hot
     * positions while OrientDB stores durable item metadata.
     */
    private void checkpointAllItemsInCurrentContext(Instant now) {
        var items = liveItemRepository.getAllActiveItems();
        for (var itemData : items) {
            PositionType type = itemData.getType();
            Instant lastTs = itemData.getEntryTime();
            Double accDist = itemData.getAccumulatedDistance();

            if (lastTs != null && type == flunav.types.PositionType.CONVEYOR) {
                var conveyor = topologyProvider.getConveyorById(itemData.getPositionId());
                if (conveyor != null) {
                    if (conveyor.getType() == flunav.types.ConveyorType.STAGING) {
                        continue;
                    }
                    long elapsed = now.toEpochMilli() - lastTs.toEpochMilli();
                    if (elapsed > 0) {
                        double moved = (elapsed / 1000.0) * conveyor.getSpeed();
                        liveItemRepository.checkpointPhysics(itemData.getId(), now, accDist + moved);
                    }
                }
            }
        }
    }

    private void cancelActivePlayback(String simulationId, PlaybackCancellationReason reason) {
        Future<?> playbackFuture = activePlaybacks.remove(simulationId);
        if (playbackFuture != null && !playbackFuture.isDone()) {
            playbackCancellationReasons.put(simulationId, reason);
            playbackFuture.cancel(true);
        }
    }

    private Object lifecycleLock(String simulationId) {
        return lifecycleLocks[Math.floorMod(simulationId.hashCode(), lifecycleLocks.length)];
    }

    private enum PlaybackCancellationReason {
        STOP,
        PAUSE,
        RESCHEDULE
    }

    private record SimulationCounts(int active, int building) {
    }

    public record SimulationRequest(String simulationId, Instant timestamp) {
    }
}
