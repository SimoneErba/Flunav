package com.flumen.backend.services;

import com.flumen.backend.models.simulation.SimulationState;
import com.flumen.backend.models.simulation.SimulationStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.graphql.GraphQlProperties.Websocket;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

@Service
public class SimulationService {

    private static final Logger logger = LoggerFactory.getLogger(SimulationService.class);

    // --- Dependencies ---
    private final OrientDBService orientDBService;
    private final HistoricalEventPlayer historicalEventPlayer;
    private final HistoricalGraphBuilder historicalGraphBuilder;
    private final WebSocketService webSocketService;

    // --- State Management ---
    private final Map<String, SimulationState> simulationCache = new ConcurrentHashMap<>();
    private final Map<String, Future<?>> activePlaybacks = new ConcurrentHashMap<>();

    // --- Concurrency Control ---
    private final Semaphore buildPermits = new Semaphore(2); // Example: Allow 2 concurrent builds
    private final Queue<SimulationRequest> waitingQueue = new ConcurrentLinkedQueue<>();

    public SimulationService(OrientDBService orientDBService, HistoricalEventPlayer historicalEventPlayer, WebSocketService webSocketService,
                            @Lazy HistoricalGraphBuilder historicalGraphBuilder) {
        this.orientDBService = orientDBService;
        this.historicalEventPlayer = historicalEventPlayer;
        this.historicalGraphBuilder = historicalGraphBuilder;
        this.webSocketService = webSocketService;
    }

    /**
     * Creates a new simulation or queues it if the system is busy.
     */
    public SimulationState createSimulation(Instant timestamp) {
        String simulationId = "sim_" + UUID.randomUUID().toString().replace("-", "");
        SimulationState state = new SimulationState(simulationId, timestamp);
        simulationCache.put(simulationId, state);
        waitingQueue.add(new SimulationRequest(simulationId, timestamp));
        processWaitingQueue();

        return state;
    }

    /**
     * Starts the live tailing event playback for a built simulation.
     */
    public void startPlayback(String simulationId, double speedFactor) {
        SimulationState state = getSimulationState(simulationId);
        if (state.getStatus() != SimulationStatus.READY) {
            throw new IllegalStateException("Simulation is not ready for playback. Current status: " + state.getStatus());
        }
        
        cancelPlayback(simulationId);
        Instant simulationStartTime = state.getTimestamp();
        state.setStatus(SimulationStatus.PLAYING);
        logger.info("Starting live tailing playback for simulation {} from {}", simulationId, simulationStartTime);

        CompletableFuture<Void> playbackFuture = historicalEventPlayer.playEvents(simulationId, simulationStartTime, speedFactor);
        activePlaybacks.put(simulationId, playbackFuture);

        playbackFuture.thenRun(() -> {
            logger.info("Playback for simulation {} has completed or was cancelled.", simulationId);
            activePlaybacks.remove(simulationId);
            updateSimulationStatus(simulationId, SimulationStatus.READY);
        });
    }
    
    /**
     * Cancels an active playback task for a given simulation.
     */
    public void cancelPlayback(String simulationId) {
        Future<?> playbackFuture = activePlaybacks.get(simulationId);
        if (playbackFuture != null && !playbackFuture.isDone()) {
            logger.warn("Attempting to cancel playback for simulation {}", simulationId);
            boolean cancelled = playbackFuture.cancel(true);
            if (cancelled) {
                logger.info("Successfully sent cancellation signal to playback task for {}", simulationId);
                activePlaybacks.remove(simulationId);
            } else {
                logger.error("Failed to cancel playback task for {}", simulationId);
            }
        }
    }

    /**
     * Completely destroys a simulation, its in-memory DB, and cancels any active playback.
     */
    public void destroySimulation(String simulationId) {
        cancelPlayback(simulationId);
        SimulationState state = simulationCache.remove(simulationId);
        if (state != null) {
            orientDBService.dropDatabase(simulationId);
            logger.info("Successfully destroyed simulation: {}", simulationId);
        } else {
            logger.warn("Attempted to destroy non-existent simulation: {}", simulationId);
        }
    }

    public SimulationState getSimulationState(String simulationId) {
        SimulationState state = simulationCache.get(simulationId);
        if (state == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Simulation not found: " + simulationId);
        }
        state.setLastHeartbeatTimestamp(Instant.now());
        return state;
    }

    /**
     * Updates the status of a simulation. Called by the HistoricalGraphBuilder.
     */
    public void updateSimulationStatus(String simulationId, SimulationStatus status) {
        SimulationState state = simulationCache.get(simulationId);
        if (state != null) {
            state.setStatus(status);
            this.webSocketService.broadcastSimulationUpdate(simulationId, state.getStatus());
            logger.info("Updated status for simulation {} to {}", simulationId, status);
        } else {
            logger.warn("Could not update status for non-existent simulation: {}", simulationId);
        }
    }

    /**
     * Checks the waiting queue and starts a new build job if a permit is available.
     * This is called after a build job completes.
     */
    public void processWaitingQueue() {
        if (!waitingQueue.isEmpty()) {
            if (buildPermits.tryAcquire()) {
                SimulationRequest request = waitingQueue.poll();
                if (request != null) {
                    logger.info("Build permit acquired for queued simulation {}. Starting build.", request.simulationId());
                    updateSimulationStatus(request.simulationId(), SimulationStatus.BUILDING);
                    orientDBService.createInMemoryDatabase(request.simulationId());
                    historicalGraphBuilder.build(request.simulationId(), request.timestamp(), buildPermits);
                } else {
                    buildPermits.release();
                }
            } else {
                logger.info("Processing queue requested, but no build permits are available.");
            }
        }
    }
    
    /**
     * Periodically runs to clean up simulations that have been abandoned (no heartbeat).
     */
    @Scheduled(fixedRate = 300_000) // Run every 5 minutes
    public void cleanupAbandonedSimulations() {
        logger.info("Running cleanup job for abandoned simulations...");
        Instant now = Instant.now();
        int abandonedCount = 0;
        
        // Use an iterator to safely remove items from the cache while iterating
        for (Iterator<Map.Entry<String, SimulationState>> it = simulationCache.entrySet().iterator(); it.hasNext();) {
            Map.Entry<String, SimulationState> entry = it.next();
            SimulationState state = entry.getValue();

            if (Duration.between(state.getLastHeartbeatTimestamp(), now).toMinutes() > 2) {
                logger.warn("Removing abandoned simulation {} (ID: {}).", entry.getKey());
                destroySimulation(entry.getKey()); // Use destroy to also cancel playback
                abandonedCount++;
            }
        }
        if (abandonedCount > 0) {
            logger.info("Cleanup complete. Removed {} abandoned simulations.", abandonedCount);
        }
    }

    public record SimulationRequest(String simulationId, Instant timestamp) {}
}