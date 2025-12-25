package com.flonav.backend.services;

import flonav.events.DomainEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.AsyncResult;
import org.springframework.stereotype.Service;

import com.flonav.backend.context.DatabaseContextHolder;
import com.flonav.backend.models.simulation.SimulationState;
import com.flonav.backend.models.simulation.SimulationStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

@Service
public class HistoricalEventPlayer {

    private static final Logger logger = LoggerFactory.getLogger(HistoricalEventPlayer.class);
    private final EventProcessor eventProcessor;
    private final ClickHouseService clickHouseService;
    private final SimulationService simulationService;
    private final WebSocketService webSocketService;

    // Define the polling interval as a constant for easy configuration
    private static final Duration POLLING_INTERVAL = Duration.ofSeconds(5);

    public HistoricalEventPlayer(EventProcessor eventProcessor, ClickHouseService clickHouseService,
            @Lazy SimulationService simulationService, WebSocketService webSocketService) {
        this.eventProcessor = eventProcessor;
        this.clickHouseService = clickHouseService;
        this.simulationService = simulationService;
        this.webSocketService = webSocketService;
    }

    @SuppressWarnings("deprecation")
    @Async("taskExecutor")
    public Future<Void> playEvents(String simulationId, Instant simulationStartTime, double initialSpeedFactor) {
        logger.info("Starting playback for simulation {} from {} at {}x speed.",
                simulationId, simulationStartTime, initialSpeedFactor);

        // Use a try-with-resources block for the context to ensure it's always cleared.
        try (DatabaseContextHolder.SimulationContext ignored = DatabaseContextHolder
                .enterSimulationContext(simulationId)) {
            SimulationState state = simulationService.getSimulationState(simulationId);

            // --- Initial State Setup ---
            state.setStatus(SimulationStatus.PLAYING);
            state.setSpeedFactor(initialSpeedFactor);
            state.setLastProcessedTimestamp(simulationStartTime); // Initialize progress
            webSocketService.broadcastSimulationUpdate(simulationId, SimulationStatus.PLAYING, Instant.now());

            Instant currentSimulationTime = simulationStartTime;

            // --- Main Playback Loop ---
            while (!Thread.currentThread().isInterrupted()) {
                long loopWallClockStartNs = System.nanoTime();

                double currentSpeedFactor = state.getSpeedFactor();
                Instant windowStartTime = currentSimulationTime;
                Instant windowEndTime = windowStartTime.plus(POLLING_INTERVAL);

                logger.debug("Polling for events for {} in window [{}, {})", simulationId, windowStartTime,
                        windowEndTime);
                List<DomainEvent> eventChunk = clickHouseService.getEventsBetween(windowStartTime, windowEndTime);

                if (!eventChunk.isEmpty()) {
                    playChunk(simulationId, eventChunk, state);
                    currentSimulationTime = eventChunk.get(eventChunk.size() - 1).getTimestamp();
                } else {
                    // No events found, advance the clock to avoid getting stuck.
                    currentSimulationTime = windowEndTime;
                    // OPTIONAL: Add logic here to detect natural completion.
                    // For example, if (windowEndTime >
                    // simulationService.getSimulationEndTime(simulationId)) {
                    // state.setStatus(SimulationStatus.COMPLETED);
                    // simulationService.broadcastStatusUpdate(simulationId,
                    // SimulationStatus.COMPLETED);
                    // logger.info("Simulation {} completed naturally.", simulationId);
                    // break; // Exit the loop
                    // }
                }

                // CRITICAL: Persistently save the progress after every chunk.
                state.setLastProcessedTimestamp(currentSimulationTime);

                // --- Dynamic Sleep Calculation ---
                long elapsedNs = System.nanoTime() - loopWallClockStartNs;
                long targetLoopDurationNs = (long) (POLLING_INTERVAL.toNanos() / currentSpeedFactor);
                long sleepNanos = targetLoopDurationNs - elapsedNs;

                if (sleepNanos > 0) {
                    Thread.sleep(sleepNanos / 1_000_000);
                }
            }
        } catch (InterruptedException e) {
            // This block is entered when the thread is interrupted by
            // playbackTask.cancel(true).
            SimulationState state = simulationService.getSimulationState(simulationId);
            // Check the official state to determine if this was a pause or a stop.
            if (state != null && state.getStatus() == SimulationStatus.PAUSED) {
                logger.info("Playback for simulation {} paused gracefully at {}.", simulationId,
                        state.getLastProcessedTimestamp());
            } else {
                logger.warn("Playback for simulation {} was stopped by interruption.", simulationId);
                if (state != null) {
                    state.setStatus(SimulationStatus.STOPPED);
                    webSocketService.broadcastSimulationUpdate(simulationId, SimulationStatus.STOPPED, Instant.now());
                }
            }
            // Preserve the interrupted status for the thread pool.
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            logger.error("An unhandled error occurred during playback for simulation {}. Setting state to FAILED.",
                    simulationId, e);
            SimulationState state = simulationService.getSimulationState(simulationId);
            if (state != null) {
                state.setStatus(SimulationStatus.FAILED);
                webSocketService.broadcastSimulationUpdate(simulationId, SimulationStatus.FAILED, Instant.now());
            }
        }

        logger.info("Playback thread for simulation {} is terminating.", simulationId);
        return new AsyncResult<>(null);
    }

    /**
     * Plays back a list of events in a time-synchronized manner with high
     * precision.
     * This method uses an efficient, interruptible wait pattern that allows for
     * immediate changes to the playback speed.
     *
     * @param simulationId The ID of the simulation being played.
     * @param eventChunk   The list of DomainEvents to play.
     * @param state        The shared SimulationState object, which holds the
     *                     current speedFactor.
     * @throws InterruptedException If the playback is cancelled while waiting.
     */
    private void playChunk(String simulationId, List<DomainEvent> eventChunk, SimulationState state)
            throws InterruptedException {
        if (eventChunk.isEmpty()) {
            return;
        }

        // 1. Set the time anchors for this chunk.
        final long chunkWallClockStartNs = System.nanoTime();
        final Instant chunkSimulationStartTime = eventChunk.get(0).getTimestamp();

        for (DomainEvent event : eventChunk) {
            // Check for cancellation at the start of each event loop.
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Playback cancelled during chunk processing.");
            }

            // We re-read the speed factor on every loop to allow for dynamic changes.
            double currentSpeedFactor = state.getSpeedFactor();

            // 2. Calculate the target wall-clock time for this event.
            Duration simulationTimeElapsed = Duration.between(chunkSimulationStartTime, event.getTimestamp());
            long scheduledWallClockOffsetNs = (long) (simulationTimeElapsed.toNanos() / currentSpeedFactor);

            // 3. Calculate how long we need to wait from this moment.
            long actualWallClockOffsetNs = System.nanoTime() - chunkWallClockStartNs;
            long waitNanos = scheduledWallClockOffsetNs - actualWallClockOffsetNs;

            // 4. Perform the efficient, interruptible wait if needed.
            if (waitNanos > 0) {
                long waitMillis = waitNanos / 1_000_000;
                int waitNanosRemainder = (int) (waitNanos % 1_000_000);

                // Wait on the shared lock object. This thread will consume zero CPU
                // until the time expires OR until another thread calls notifyAll() on the lock.
                synchronized (state.getTimingLock()) {
                    state.getTimingLock().wait(waitMillis, waitNanosRemainder);
                }
            }

            // After waking up, the thread might still be slightly ahead of schedule.
            // A final, brief "spin-wait" ensures nanosecond precision. This loop
            // will be very short and consumes minimal CPU.
            while ((System.nanoTime() - chunkWallClockStartNs) < scheduledWallClockOffsetNs) {
                // In Java 9+, Thread.onSpinWait() is a hint to the CPU that we're in a tight
                // loop.
                // For Java 8, this empty loop is sufficient.
                Thread.onSpinWait();
            }

            try {
                eventProcessor.processEvent(event);
            } catch (Exception e) {
                logger.warn("Failed to process event {}", event, e);
            }
        }
    }
}