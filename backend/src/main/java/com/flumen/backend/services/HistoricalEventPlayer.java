package com.flumen.backend.services;

import flumen.events.DomainEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import com.flumen.backend.context.DatabaseContextHolder;
import com.flumen.backend.models.simulation.SimulationState;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Service
public class HistoricalEventPlayer {

    private static final Logger logger = LoggerFactory.getLogger(HistoricalEventPlayer.class);
    private final EventProcessor eventProcessor;
    private final ClickHouseService clickHouseService;
    private final SimulationService simulationService;

    // Define the polling interval as a constant for easy configuration
    private static final Duration POLLING_INTERVAL = Duration.ofSeconds(5);

    public HistoricalEventPlayer(EventProcessor eventProcessor, ClickHouseService clickHouseService, SimulationService simulationService) {
        this.eventProcessor = eventProcessor;
        this.clickHouseService = clickHouseService;
        this.simulationService = simulationService;
    }

    @Async("taskExecutor")
    public CompletableFuture<Void> playEvents(String simulationId, Instant simulationStartTime, double initialSpeedFactor) {
        logger.info("Starting POLLING playback for simulation {} from {} at {}x speed.",
                simulationId, simulationStartTime, initialSpeedFactor);

        // This is the "simulation clock" which we will advance in chunks.
        Instant currentSimulationTime = simulationStartTime;
        DatabaseContextHolder.setSimulationId(simulationId);
        SimulationState state = simulationService.getSimulationState(simulationId);
        state.setSpeedFactor(initialSpeedFactor);
        // --- THE OUTER POLLING LOOP ---
        // This loop runs until the player is cancelled.
        while (!Thread.currentThread().isInterrupted()) {
            try {
                double currentSpeedFactor = state.getSpeedFactor();
                // 1. Define the time window for the next database query.
                Instant windowStartTime = currentSimulationTime;
                Instant windowEndTime = windowStartTime.plus(POLLING_INTERVAL);

                // 2. Fetch the chunk of events for this window from ClickHouse.
                logger.debug("Polling for events for {} in window [{}, {})", simulationId, windowStartTime, windowEndTime);
                List<DomainEvent> eventChunk = clickHouseService.getEventsBetween(windowStartTime, windowEndTime);

                if (!eventChunk.isEmpty()) {
                    logger.info("Found {} events in chunk for {}. Starting smooth playback.", eventChunk.size(), simulationId);
                    
                    // 3. Play this specific chunk with high fidelity.
                    playChunk(simulationId, eventChunk, state);
                    
                    // 4. Advance the simulation clock to the timestamp of the last event we just played.
                    currentSimulationTime = eventChunk.get(eventChunk.size() - 1).getTimestamp();
                } else {
                    logger.trace("No events found in window for {}.", simulationId);
                    // If no events were found, we still advance the clock to the end of the window
                    // to avoid getting stuck querying the same empty time range forever.
                    currentSimulationTime = windowEndTime;
                }

                // 5. Wait for the next polling cycle.
                // The sleep duration is adjusted by the speed factor.
                long sleepMillis = (long) (POLLING_INTERVAL.toMillis() / currentSpeedFactor);
                logger.debug("Chunk processed. Sleeping for {}ms before next poll.", sleepMillis);
                Thread.sleep(sleepMillis);

            } catch (InterruptedException e) {
                logger.warn("Polling playback for simulation {} was interrupted. Stopping.", simulationId);
                Thread.currentThread().interrupt(); // Re-set the interrupt flag
                break; // Exit the main loop
            } catch (Exception e) {
                logger.error("An unhandled error occurred during polling playback for simulation {}. Stopping.", simulationId, e);
                break;
            } finally {
                DatabaseContextHolder.clearSimulation();
            }
        }

        logger.info("Polling playback finished for simulation {}.", simulationId);
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Plays back a list of events in a time-synchronized manner with high precision.
     * This method uses an efficient, interruptible wait pattern that allows for
     * immediate changes to the playback speed.
     *
     * @param simulationId The ID of the simulation being played.
     * @param eventChunk   The list of DomainEvents to play.
     * @param state        The shared SimulationState object, which holds the current speedFactor.
     * @throws InterruptedException If the playback is cancelled while waiting.
     */
    private void playChunk(String simulationId, List<DomainEvent> eventChunk, SimulationState state) throws InterruptedException {
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
                // In Java 9+, Thread.onSpinWait() is a hint to the CPU that we're in a tight loop.
                // For Java 8, this empty loop is sufficient.
                Thread.onSpinWait();
            }

            eventProcessor.processEvent(event); 
        }
    }
}