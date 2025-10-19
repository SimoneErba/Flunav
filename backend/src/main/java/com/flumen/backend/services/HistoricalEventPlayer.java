package com.flumen.backend.services;

import flumen.events.DomainEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import com.flumen.backend.context.DatabaseContextHolder;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Service
public class HistoricalEventPlayer {

    private static final Logger logger = LoggerFactory.getLogger(HistoricalEventPlayer.class);
    private final EventProcessor eventProcessor;
    private final ClickHouseService clickHouseService;

    // Define the polling interval as a constant for easy configuration
    private static final Duration POLLING_INTERVAL = Duration.ofSeconds(60);

    public HistoricalEventPlayer(EventProcessor eventProcessor, ClickHouseService clickHouseService) {
        this.eventProcessor = eventProcessor;
        this.clickHouseService = clickHouseService;
    }

    @Async("taskExecutor")
    public CompletableFuture<Void> playEvents(String simulationId, Instant simulationStartTime, double speedFactor) {
        logger.info("Starting POLLING playback for simulation {} from {} at {}x speed.",
                simulationId, simulationStartTime, speedFactor);

        // This is the "simulation clock" which we will advance in chunks.
        Instant currentSimulationTime = simulationStartTime;
        DatabaseContextHolder.setSimulationId(simulationId);
        // --- THE OUTER POLLING LOOP ---
        // This loop runs until the player is cancelled.
        while (!Thread.currentThread().isInterrupted()) {
            try {
                // 1. Define the time window for the next database query.
                Instant windowStartTime = currentSimulationTime;
                Instant windowEndTime = windowStartTime.plus(POLLING_INTERVAL);

                // 2. Fetch the chunk of events for this window from ClickHouse.
                logger.debug("Polling for events for {} in window [{}, {})", simulationId, windowStartTime, windowEndTime);
                List<DomainEvent> eventChunk = clickHouseService.getEventsBetween(windowStartTime, windowEndTime);

                if (!eventChunk.isEmpty()) {
                    logger.info("Found {} events in chunk for {}. Starting smooth playback.", eventChunk.size(), simulationId);
                    
                    // 3. Play this specific chunk with high fidelity.
                    playChunk(simulationId, eventChunk, speedFactor);
                    
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
                long sleepMillis = (long) (POLLING_INTERVAL.toMillis() / speedFactor);
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
                DatabaseContextHolder.clear();
            }
        }

        logger.info("Polling playback finished for simulation {}.", simulationId);
        return CompletableFuture.completedFuture(null);
    }

    /**
     * A helper method to play a single chunk of events using the high-fidelity,
     * self-correcting clock model.
     */
    private void playChunk(String simulationId, List<DomainEvent> eventChunk, double speedFactor) throws InterruptedException {
        final long chunkWallClockStartNs = System.nanoTime();
        final Instant chunkSimulationStartTime = eventChunk.get(0).getTimestamp();

        for (DomainEvent event : eventChunk) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Playback cancelled during chunk processing.");
            }

            // Synchronize to the clock for this specific event
            Duration simulationTimeElapsed = Duration.between(chunkSimulationStartTime, event.getTimestamp());
            long scheduledWallClockOffsetNs = (long) (simulationTimeElapsed.toNanos() / speedFactor);

            while (true) {
                long actualWallClockOffsetNs = System.nanoTime() - chunkWallClockStartNs;
                if (actualWallClockOffsetNs >= scheduledWallClockOffsetNs) {
                    break;
                }
                Thread.sleep(1); // Sleep in a tight loop for accuracy
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("Playback cancelled while waiting for next event in chunk.");
                }
            }

            // Process the event
            eventProcessor.processEvent(event);
        }
    }
}