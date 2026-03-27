package com.flunav.backend.services;

import com.flunav.backend.context.DatabaseContextHolder;
import com.flunav.backend.models.simulation.SimulationState;
import com.flunav.backend.models.simulation.SimulationStatus;
import com.flunav.backend.repositories.LiveSimulationRepository;
import com.flunav.backend.repositories.LiveSimulationRepository.SimulationMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import com.flunav.backend.repositories.LiveItemRepository;

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

    @org.springframework.beans.factory.annotation.Autowired
    @Lazy
    private EventProcessor eventProcessor;

    @org.springframework.beans.factory.annotation.Autowired
    private TimeService timeService;

    private final Map<String, SimulationState> simulationCache = new ConcurrentHashMap<>();
    private final Map<String, Future<?>> activePlaybacks = new ConcurrentHashMap<>();
    private final Semaphore buildPermits = new Semaphore(2);
    private final Queue<SimulationRequest> waitingQueue = new ConcurrentLinkedQueue<>();

    public SimulationService(OrientDBService orientDBService, HistoricalEventPlayer historicalEventPlayer,
            WebSocketService webSocketService,
            @Lazy HistoricalGraphBuilder historicalGraphBuilder,
            LiveItemRepository liveItemRepository,
            LiveSimulationRepository liveSimulationRepository,
            @org.springframework.context.annotation.Lazy TopologyProvider topologyProvider) {
        this.orientDBService = orientDBService;
        this.historicalEventPlayer = historicalEventPlayer;
        this.historicalGraphBuilder = historicalGraphBuilder;
        this.webSocketService = webSocketService;
        this.liveItemRepository = liveItemRepository;
        this.liveSimulationRepository = liveSimulationRepository;
        this.topologyProvider = topologyProvider;
    }

    public SimulationState createSimulation(Instant timestamp) {
        String simulationId = "sim_" + UUID.randomUUID().toString().replace("-", "");
        return createSimulation(simulationId, timestamp);
    }

    public SimulationState createSimulation(String simulationId, Instant timestamp) {
        SimulationState state = new SimulationState(simulationId, timestamp);
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

    public void startPlayback(String simulationId, double speedFactor) {
        SimulationState state = getSimulationState(simulationId);
        if (state.getStatus() != SimulationStatus.READY && state.getStatus() != SimulationStatus.STOPPED
                && state.getStatus() != SimulationStatus.PAUSED) {
            throw new IllegalStateException("Simulation not ready for playback. Current status: " + state.getStatus());
        }
        Instant simulationStartTime = state.getLastProcessedTimestamp() != null ? state.getLastProcessedTimestamp()
                : state.getTimestamp();
        state.setStatus(SimulationStatus.PLAYING);
        state.setSpeedFactor(speedFactor);
        persistState(state);
        var playbackFuture = historicalEventPlayer.playEvents(simulationId, simulationStartTime, speedFactor);
        activePlaybacks.put(simulationId, playbackFuture);
    }

    public void cancelPlayback(String simulationId) {
        Future<?> playbackFuture = activePlaybacks.get(simulationId);
        SimulationState state = simulationCache.get(simulationId);
        if (playbackFuture != null && !playbackFuture.isDone()) {
            if (state != null) {
                state.setStatus(SimulationStatus.STOPPED);
                persistState(state);
            }
            playbackFuture.cancel(true);
            activePlaybacks.remove(simulationId);
        }
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
        playbackTask.cancel(true);
        activePlaybacks.remove(simulationId);
    }

    public void updatePlaybackSpeed(String simulationId, double newSpeedFactor) {
        SimulationState state = getSimulationState(simulationId);
        state.setSpeedFactor(newSpeedFactor);
        persistState(state);
    }

    public void destroySimulation(String simulationId) {
        cancelPlayback(simulationId);
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
        updateHeartbeat(simulationId);
        return state;
    }

    public void updateHeartbeat(String simulationId) {
        Instant now = Instant.now();
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
            persistState(state);
            this.webSocketService.broadcastSimulationUpdate(simulationId, state.getStatus(), timestamp);
        }
    }

    public void processWaitingQueue() {
        if (!waitingQueue.isEmpty() && buildPermits.tryAcquire()) {
            SimulationRequest request = waitingQueue.poll();
            if (request != null) {
                updateSimulationStatus(request.simulationId(), SimulationStatus.BUILDING, Instant.now());
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
        Instant now = Instant.now();
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

    public void processEventsUntil(String simulationId, Instant targetTime) {
        SimulationState state = loadOrRefreshSimulationState(simulationId);
        if (state == null)
            return;

        while (true) {
            var event = state.getInternalEventQueue().peek();
            if (event == null || event.getTimestamp().isAfter(targetTime)) {
                break;
            }

            state.getInternalEventQueue().poll();

            checkpointAllItems(simulationId, event.getTimestamp());
            timeService.useFixedClock(event.getTimestamp());

            if (event instanceof flunav.events.EntityEvent ee) {
                state.getScheduledEventsByItem().remove(ee.getEntityId(), event);
            }

            try (var ctx = DatabaseContextHolder.enterSimulationContext(simulationId)) {
                eventProcessor.processEventWithoutBroadcast(event);
            }
        }

        checkpointAllItems(simulationId, targetTime);
        state.setLastProcessedTimestamp(targetTime);
        persistState(state);
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
                    metadata.speedFactor());
            simulationCache.put(simulationId, restored);
            return restored;
        }

        cached.setStatus(metadata.status());
        cached.setLastHeartbeatTimestamp(metadata.lastHeartbeatTimestamp());
        cached.setLastProcessedTimestamp(metadata.lastProcessedTimestamp());
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
                state.getSpeedFactor()));
    }

    private void checkpointAllItems(String simulationId, Instant now) {
        try (var ctx = DatabaseContextHolder.enterSimulationContext(simulationId)) {
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
    }

    public record SimulationRequest(String simulationId, Instant timestamp) {
    }
}
