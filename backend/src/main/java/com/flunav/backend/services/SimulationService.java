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
import com.flunav.backend.repositories.LiveItemRepository;
import com.flunav.backend.repositories.LiveSimulationRepository;
import com.flunav.backend.repositories.LiveSimulationRepository.SimulationMetadata;

import flunav.events.DomainEvent;
import flunav.types.PositionType;

@Service
public class SimulationService {

    private static final Logger logger = LoggerFactory.getLogger(SimulationService.class);

    private final OrientDBService orientDBService;
    private final HistoricalEventPlayer historicalEventPlayer;
    private final HistoricalGraphBuilder historicalGraphBuilder;
    private final WebSocketService webSocketService;
    private final LiveItemRepository liveItemRepository;
    private final LiveSimulationRepository liveSimulationRepository;
    private final TopologyProvider topologyProvider;
    private final ItemMovementProcessor itemMovementProcessor;
    private final int maxActiveSimulations;
    private final int maxBuildingSimulations;
    private final long maxActiveItemsPerSimulation;
    private final long minFreeMemoryBytes;

    @org.springframework.beans.factory.annotation.Autowired
    @Lazy
    private EventProcessor eventProcessor;

    @org.springframework.beans.factory.annotation.Autowired
    private TimeService timeService;

    private final Map<String, SimulationState> simulationCache = new ConcurrentHashMap<>();
    private final Map<String, Future<?>> activePlaybacks = new ConcurrentHashMap<>();
    private final Map<String, PlaybackCancellationReason> playbackCancellationReasons = new ConcurrentHashMap<>();
    private final Semaphore buildPermits;
    private final Queue<SimulationRequest> waitingQueue = new ConcurrentLinkedQueue<>();

    public SimulationService(OrientDBService orientDBService, HistoricalEventPlayer historicalEventPlayer,
            WebSocketService webSocketService,
            @Lazy HistoricalGraphBuilder historicalGraphBuilder,
            LiveItemRepository liveItemRepository,
            LiveSimulationRepository liveSimulationRepository,
            @org.springframework.context.annotation.Lazy TopologyProvider topologyProvider,
            @Lazy ItemMovementProcessor itemMovementProcessor,
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
        this.topologyProvider = topologyProvider;
        this.itemMovementProcessor = itemMovementProcessor;
        this.maxActiveSimulations = maxActiveSimulations;
        this.maxBuildingSimulations = maxBuildingSimulations;
        this.maxActiveItemsPerSimulation = maxActiveItemsPerSimulation;
        this.minFreeMemoryBytes = minFreeMemoryBytes;
        this.buildPermits = new Semaphore(maxBuildingSimulations > 0 ? maxBuildingSimulations : Integer.MAX_VALUE);
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
    public SimulationState createSimulation(String simulationId, Instant timestamp) {
        enforceSimulationCapacity();
        SimulationState state = new SimulationState(simulationId, timestamp, SimulationStatus.QUEUED,
                timeService.physicalNow(), null, 1.0, 0.0);
        simulationCache.put(simulationId, state);
        persistState(state);
        waitingQueue.add(new SimulationRequest(simulationId, timestamp));
        processWaitingQueue();
        return state;
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

    private void startPlaybackWorker(String simulationId, SimulationState state, Instant simulationStartTime,
            double speedFactor) {
        state.setStatus(SimulationStatus.PLAYING);
        state.setSpeedFactor(speedFactor);
        state.setLastProcessedTimestamp(simulationStartTime);
        persistState(state);
        webSocketService.broadcastSimulationUpdate(simulationId, SimulationStatus.PLAYING, simulationStartTime);
        var playbackFuture = historicalEventPlayer.playEvents(simulationId, simulationStartTime, speedFactor);
        activePlaybacks.put(simulationId, playbackFuture);
    }

    public void cancelPlayback(String simulationId) {
        Future<?> playbackFuture = activePlaybacks.get(simulationId);
        if (playbackFuture == null || playbackFuture.isDone()) {
            return;
        }

        SimulationState state = simulationCache.get(simulationId);
        if (state != null) {
            state.setStatus(SimulationStatus.STOPPED);
            persistState(state);
        }
        cancelActivePlayback(simulationId, PlaybackCancellationReason.STOP);
    }

    public void pauseSimulation(String simulationId) {
        SimulationState state = simulationCache.get(simulationId);
        Future<?> playbackTask = activePlaybacks.get(simulationId);
        if (state == null || playbackTask == null) {
            throw new IllegalStateException("Simulation " + simulationId + " does not exist or is not running.");
        }
        if (state.getStatus() != SimulationStatus.PLAYING) {
            throw new IllegalStateException(
                    "Simulation " + simulationId + " is not playing. Current state: " + state.getStatus());
        }
        state.setStatus(SimulationStatus.PAUSED);
        persistState(state);
        webSocketService.broadcastSimulationUpdate(simulationId, SimulationStatus.PAUSED,
                state.getLastProcessedTimestamp());
        cancelActivePlayback(simulationId, PlaybackCancellationReason.PAUSE);
    }

    /**
     * Changes playback speed without changing event timestamps.
     * Active playback is cancelled, movement physics are checkpointed at the same
     * virtual instant, and scheduling is rebuilt with the new wall-clock delay.
     */
    public void updatePlaybackSpeed(String simulationId, double newSpeedFactor) {
        validateSpeedFactor(newSpeedFactor);
        SimulationState state = getSimulationState(simulationId);
        state.setSpeedFactor(newSpeedFactor);
        persistState(state);
        webSocketService.broadcastSpeedUpdate(simulationId, newSpeedFactor, getSimulationClock(state));

        if (state.getStatus() != SimulationStatus.PLAYING) {
            return;
        }

        Instant restartTimestamp = getSimulationClock(state);
        cancelActivePlayback(simulationId, PlaybackCancellationReason.RESCHEDULE);
        state.setStatus(SimulationStatus.PLAYING);
        state.setLastProcessedTimestamp(restartTimestamp);
        persistState(state);
        recalculateMovementSchedules(simulationId, restartTimestamp);
        startPlaybackWorker(simulationId, state, restartTimestamp, newSpeedFactor);
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
        liveSimulationRepository.deleteState(simulationId);

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
            if (Duration.between(heartbeat.lastHeartbeatTimestamp(), now).toMinutes() > 2) {
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
            // Remove existing event for this entity if it's the same type (or just remove
            // any scheduled for same entity to be safe)
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
        SimulationMetadata metadata = liveSimulationRepository.getState(simulationId).orElse(null);

        if (metadata == null) {
            return cached;
        }

        if (cached == null) {
            SimulationState restored = new SimulationState(
                    metadata.simulationId(),
                    metadata.timestamp(),
                    metadata.status(),
                    metadata.lastHeartbeatTimestamp(),
                    metadata.lastProcessedTimestamp(),
                    metadata.speedFactor(),
                    metadata.buildProgress());
            simulationCache.put(simulationId, restored);
            return restored;
        }

        cached.setStatus(metadata.status());
        cached.setLastHeartbeatTimestamp(metadata.lastHeartbeatTimestamp());
        cached.setLastProcessedTimestamp(metadata.lastProcessedTimestamp());
        cached.setBuildProgress(metadata.buildProgress());
        if (Double.compare(cached.getSpeedFactor(), metadata.speedFactor()) != 0) {
            cached.setSpeedFactor(metadata.speedFactor());
        }
        return cached;
    }

    private void persistState(SimulationState state) {
        liveSimulationRepository.saveState(new SimulationMetadata(
                state.getId(),
                state.getTimestamp(),
                state.getStatus(),
                state.getLastHeartbeatTimestamp(),
                state.getLastProcessedTimestamp(),
                state.getSpeedFactor(),
                state.getBuildProgress()));
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
