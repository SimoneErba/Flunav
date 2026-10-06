package com.flunav.backend.services;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import com.flunav.backend.models.simulation.LiveInputState;
import com.flunav.backend.models.multisimulation.MultiSimulationBaseline;
import com.flunav.backend.repositories.LiveItemRepository;
import com.flunav.backend.repositories.LiveConveyorRepository;
import com.flunav.backend.repositories.LiveSimulationRepository;
import com.flunav.backend.repositories.AnomalyObservationRepository;
import flunav.events.AnomalyEvaluationTickEvent;

import flunav.events.DomainEvent;
import flunav.types.PositionType;

/**
 * Coordinates simulation builds, forks, and cleanup through dedicated runtime and playback owners.
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

    private final WebSocketService webSocketService;
    private final LiveItemRepository liveItemRepository;
    private final LiveConveyorRepository liveConveyorRepository;
    private final LiveSimulationRepository liveSimulationRepository;
    private final TopologyProvider topologyProvider;
    private final ItemMovementProcessor itemMovementProcessor;
    private final ClickHouseService clickHouseService;
    private final AnomalyEngine anomalyEngine;
    private final AnomalyObservationRepository anomalyObservationRepository;

    private final EventProcessor eventProcessor;

    private final TimeService timeService;

    private final SimulationInputService simulationInputService;

    private final SimulationRuntimeState runtimeState;
    private final SimulationLifecycleManager lifecycleManager;
    private final SimulationPlaybackLifecycle playbackLifecycle;
    private final SimulationInternalEventProjector internalEventProjector;

    public SimulationService(
            WebSocketService webSocketService,
            LiveItemRepository liveItemRepository,
            LiveConveyorRepository liveConveyorRepository,
            LiveSimulationRepository liveSimulationRepository,
            @Lazy TopologyProvider topologyProvider,
            @Lazy ItemMovementProcessor itemMovementProcessor,
            ClickHouseService clickHouseService,
            @Lazy AnomalyEngine anomalyEngine,
            AnomalyObservationRepository anomalyObservationRepository,
            @Lazy EventProcessor eventProcessor,
            TimeService timeService,
            SimulationInputService simulationInputService,
            SimulationRuntimeState runtimeState,
            SimulationPlaybackLifecycle playbackLifecycle,
            SimulationInternalEventProjector internalEventProjector,
            SimulationLifecycleManager lifecycleManager) {
        this.webSocketService = webSocketService;
        this.liveItemRepository = liveItemRepository;
        this.liveConveyorRepository = liveConveyorRepository;
        this.liveSimulationRepository = liveSimulationRepository;
        this.topologyProvider = topologyProvider;
        this.itemMovementProcessor = itemMovementProcessor;
        this.clickHouseService = clickHouseService;
        this.anomalyEngine = anomalyEngine;
        this.anomalyObservationRepository = anomalyObservationRepository;
        this.eventProcessor = eventProcessor;
        this.timeService = timeService;
        this.simulationInputService = simulationInputService;
        this.runtimeState = runtimeState;
        this.playbackLifecycle = playbackLifecycle;
        this.internalEventProjector = internalEventProjector;
        this.lifecycleManager = lifecycleManager;
    }

    public SimulationState createSimulation(Instant timestamp) {
        String simulationId = "sim_" + UUID.randomUUID().toString().replace("-", "");
        return createSimulation(simulationId, timestamp);
    }

    /** Creates an isolated topology-only runtime used by one fast multi-simulation run. */
    public SimulationState createMultiSimulationRuntime(
            String simulationId,
            Instant start,
            MultiSimulationBaseline baseline) {
        return lifecycleManager.createMultiSimulationRuntime(simulationId, start, baseline);
    }

    /**
     * Persists and queues a simulation build before async work starts.
     * Keeping state in Redis as well as memory lets controllers and workers recover
     * progress even when they observe different service instances or threads.
     */
    public synchronized SimulationState createSimulation(String simulationId, Instant timestamp) {
        return lifecycleManager.createSimulation(simulationId, timestamp);
    }

    /**
     * Forks live state or a paused historical simulation into an isolated what-if
     * branch. The source snapshot and live-input registration share the event
     * processor barrier so no committed live event can fall into a handoff gap.
     */
    public synchronized SimulationState openScenario(MultiSimulationBaseline baseline) {
        return lifecycleManager.openScenario(baseline);
    }

    public synchronized SimulationState createWhatIf(String sourceSimulationId) {
        return lifecycleManager.createWhatIf(sourceSimulationId);
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
            runtimeState.persistState(state);
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

    void freezeLiveInput(SimulationState state) {
        if (state.getLiveInputState() == LiveInputState.FROZEN) return;
        state.setLiveInputState(LiveInputState.FROZEN);
        simulationInputService.unregister(state.getId());
        runtimeState.persistState(state);
        webSocketService.broadcastSimulationMode(state);
    }

    /** Detaches an isolated scenario from live intake while retaining its simulation lifecycle. */
    public void isolateFromLiveInput(String simulationId) {
        synchronized (runtimeState.lifecycleLock(simulationId)) {
            freezeLiveInput(getSimulationState(simulationId));
        }
    }

    public SimulationState getOrCreateSimulation(String simulationId, Instant timestamp) {
        SimulationState cached = runtimeState.simulationCache.get(simulationId);
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
        playbackLifecycle.startPlayback(simulationId, speedFactor);
    }

    void startPlaybackWorker(String simulationId, SimulationState state, Instant start, double speed) {
        playbackLifecycle.startPlaybackWorker(simulationId, state, start, speed);
    }

    public void cancelPlayback(String simulationId) {
        playbackLifecycle.cancelPlayback(simulationId);
    }

    public void pauseSimulation(String simulationId) {
        playbackLifecycle.pauseSimulation(simulationId);
    }

    /**
     * Changes playback speed without changing event timestamps.
     * Active playback is cancelled, movement physics are checkpointed at the same
     * virtual instant, and scheduling is rebuilt with the new wall-clock delay.
     */
    public void updatePlaybackSpeed(String simulationId, double newSpeedFactor) {
        playbackLifecycle.updatePlaybackSpeed(simulationId, newSpeedFactor);
    }

    public boolean consumePlaybackRescheduleInterruption(String simulationId) {
        return playbackLifecycle.consumePlaybackRescheduleInterruption(simulationId);
    }

    /**
     * Destroys all isolated state for a simulation.
     * Standard simulations drop their OrientDB database and Redis namespace;
     * multi-simulation runs discard their in-process graph and hot state instead.
     */
    public void destroySimulation(String simulationId) {
        lifecycleManager.destroySimulation(simulationId);
    }

    public SimulationState getSimulationState(String simulationId) {
        SimulationState state = runtimeState.loadOrRefreshSimulationState(simulationId);
        if (state == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Simulation not found: " + simulationId);
        }
        return state;
    }

    public void updateHeartbeat(String simulationId) {
        Instant now = timeService.physicalNow();
        SimulationState state = runtimeState.loadOrRefreshSimulationState(simulationId);
        if (state != null) {
            state.setLastHeartbeatTimestamp(now);
            runtimeState.persistState(state);
            if (state.getSourceSimulationId() != null) updateHeartbeat(state.getSourceSimulationId());
            return;
        }

        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Simulation not found: " + simulationId);
    }

    public void updateSimulationStatus(String simulationId, SimulationStatus status, Instant timestamp) {
        SimulationState state = runtimeState.loadOrRefreshSimulationState(simulationId);
        if (state != null) {
            state.setStatus(status);
            if (timestamp != null) {
                state.setLastProcessedTimestamp(timestamp);
            }
            if (status == SimulationStatus.READY) {
                state.setBuildProgress(100.0);
            }
            runtimeState.persistState(state);
            Instant updateTimestamp = timestamp != null ? timestamp : timeService.physicalNow();
            this.webSocketService.broadcastSimulationUpdate(simulationId, state.getStatus(), updateTimestamp,
                    state.getBuildProgress());
        }
    }

    /** Advances a paused scenario clock without overwriting an active playback status. */
    public void updateSimulationProgress(String simulationId, Instant timestamp) {
        SimulationState state = runtimeState.loadOrRefreshSimulationState(simulationId);
        if (state == null || timestamp == null) {
            return;
        }
        synchronized (state.getExecutionLock()) {
            if (state.getStatus() != SimulationStatus.PLAYING) {
                Instant previous = state.getLastProcessedTimestamp();
                if (previous == null || timestamp.isAfter(previous)) {
                    state.setLastProcessedTimestamp(timestamp);
                }
                runtimeState.persistState(state);
                webSocketService.broadcastSimulationUpdate(simulationId, state.getStatus(),
                        state.getLastProcessedTimestamp(), state.getBuildProgress());
            }
        }
    }

    public void updateBuildProgress(String simulationId, double progress, Instant processedTimestamp) {
        SimulationState state = runtimeState.loadOrRefreshSimulationState(simulationId);
        if (state == null) {
            return;
        }

        double boundedProgress = Math.max(0.0, Math.min(100.0, progress));
        state.setBuildProgress(Math.max(state.getBuildProgress(), boundedProgress));
        runtimeState.persistState(state);
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
        lifecycleManager.processWaitingQueue();
    }

    @Scheduled(fixedRate = 300_000)
    public void cleanupAbandonedSimulations() {
        lifecycleManager.cleanupAbandonedSimulations();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void cleanupAbandonedSimulationsOnStartup() {
        cleanupAbandonedSimulations();
    }

    public SimulationState getCurrentSimulation() {
        var id = DatabaseContextHolder.getSimulationId();
        var state = id != null ? runtimeState.loadOrRefreshSimulationState(id) : null;
        if (id != null) {
            logger.debug("getCurrentSimulation for id {}: found state? {}", id, (state != null));
        }
        return state;
    }

    boolean hasWhatIfChild(String simulationId) {
        return runtimeState.simulationCache.values().stream().anyMatch(state -> simulationId.equals(state.getSourceSimulationId()));
    }

    void requireNoWhatIfChild(String simulationId) {
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
            DomainEvent previous = state.getScheduledEventsByItem().put(ee.getEntityId(), event);
            state.getInternalEventQueue().invalidate(previous);
            state.getInternalEventQueue().add(event);
        }
    }

    /** Planned arrivals have distinct ids and never replace another item's projected transition. */
    public void addPlannedInputEvents(java.util.Collection<? extends DomainEvent> events) {
        SimulationState state = getCurrentSimulation();
        if (state == null) {
            throw new IllegalStateException("A simulation context is required");
        }
        state.getInternalEventQueue().addAll(events);
    }

    /** Enqueues a planned infrastructure event without replacing another event for that conveyor. */
    public void addPlannedInternalEvent(flunav.events.DomainEvent event) {
        SimulationState state = getCurrentSimulation();
        if (state == null) {
            throw new IllegalStateException("A simulation context is required");
        }
        state.getInternalEventQueue().add(event);
    }

    public void cancelInternalEvent(String itemId) {
        SimulationState state = getCurrentSimulation();
        if (state != null) {
            state.getInternalEventQueue().invalidate(state.getScheduledEventsByItem().remove(itemId));
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
        SimulationState state = runtimeState.loadOrRefreshSimulationState(simulationId);
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
     * Pulling from the queue here centralizes virtual-time advancement and context
     * re-entry for both historical builds and interactive playback.
     */
    public DomainEvent processNextInternalEvent(String simulationId) {
        SimulationState state = runtimeState.loadOrRefreshSimulationState(simulationId);
        if (state == null) {
            return null;
        }

        DomainEvent event = state.getInternalEventQueue().poll();
        if (event == null) {
            return null;
        }

        internalEventProjector.process(simulationId, state, event);
        return event;
    }

    /**
     * Advances all moving items to a virtual timestamp without firing a new event.
     * This preserves accumulated conveyor distance for future projections and for
     * graph reads that happen between scheduled internal events.
     */
    public void checkpointSimulationAt(String simulationId, Instant targetTime) {
        SimulationState state = runtimeState.loadOrRefreshSimulationState(simulationId);
        if (state == null) {
            return;
        }

        if (state.getStatus() != SimulationStatus.BUILDING && state.getStatus() != SimulationStatus.QUEUED) {
            freezeLiveInputIfFuture(simulationId, targetTime);
        }
        checkpointAllItems(simulationId, targetTime);
        state.setLastProcessedTimestamp(targetTime);
        runtimeState.persistState(state);
    }


    public void updateLastProcessedTimestamp(String simulationId, Instant timestamp) {
        SimulationState state = runtimeState.loadOrRefreshSimulationState(simulationId);
        if (state != null) {
            state.setLastProcessedTimestamp(timestamp);
            runtimeState.persistState(state);
        }
    }

    /**
     * Seeds each cadence at its first unprocessed UTC-aligned boundary. Tick state
     * is read inside the simulation namespace so restarts do not duplicate a
     * boundary that was already committed.
     */
    public void initializeAnomalySchedule(String simulationId, Instant startingTimestamp) {
        SimulationState state = runtimeState.loadOrRefreshSimulationState(simulationId);
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
    void recalculateMovementSchedules(String simulationId, Instant restartTimestamp) {
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
     * positions while OrientDB stores durable item metadata. Flow-paused items and
     * stopped belts retain their checkpoints until admission resumes movement.
     */
    private void checkpointAllItemsInCurrentContext(Instant now) {
        var items = liveItemRepository.getAllActiveItems();
        for (var itemData : items) {
            if (itemData.isMovementPaused() || itemData.isFlowPaused()) {
                continue;
            }
            PositionType type = itemData.getType();
            Instant lastTs = itemData.getEntryTime();
            double progress = itemData.getAccumulatedDistance();

            if (lastTs != null && type == flunav.types.PositionType.CONVEYOR) {
                var conveyor = topologyProvider.getConveyorById(itemData.getPositionId());
                if (conveyor != null && conveyor.isActive() && conveyor.getSpeed() > 0
                        && !liveConveyorRepository.isFlowStopped(conveyor.getId())) {
                    if (conveyor.getType() == flunav.types.ConveyorType.STAGING) {
                        continue;
                    }
                    long elapsed = now.toEpochMilli() - lastTs.toEpochMilli();
                    if (elapsed > 0) {
                        double progressDelta = conveyor.getLength() > 0
                                ? (elapsed / 1000.0) * conveyor.getSpeed() / conveyor.getLength() * 100.0
                                : 0.0;
                        liveItemRepository.checkpointPhysics(itemData.getId(), now, progress + progressDelta);
                    }
                }
            }
        }
    }

    public record SimulationRequest(String simulationId, Instant timestamp) {
    }
}
