package com.flunav.backend.services;

import flunav.events.DomainEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.AsyncResult;
import org.springframework.stereotype.Service;

import com.flunav.backend.context.DatabaseContextHolder;
import com.flunav.backend.models.simulation.SimulationState;
import com.flunav.backend.models.simulation.SimulationStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Future;

@Service
public class HistoricalEventPlayer {

    private static final Logger logger = LoggerFactory.getLogger(HistoricalEventPlayer.class);
    private final EventProcessor eventProcessor;
    private final ClickHouseService clickHouseService;
    private final SimulationService simulationService;
    private final WebSocketService webSocketService;
    private final TimeService timeService;

    // Define the polling interval as a constant for easy configuration
    private static final Duration POLLING_INTERVAL = Duration.ofSeconds(5);

    public HistoricalEventPlayer(EventProcessor eventProcessor, ClickHouseService clickHouseService,
            @Lazy SimulationService simulationService, WebSocketService webSocketService, TimeService timeService) {
        this.eventProcessor = eventProcessor;
        this.clickHouseService = clickHouseService;
        this.simulationService = simulationService;
        this.webSocketService = webSocketService;
        this.timeService = timeService;
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
            simulationService.updateLastProcessedTimestamp(simulationId, simulationStartTime);
            webSocketService.broadcastSimulationUpdate(simulationId, SimulationStatus.PLAYING, simulationStartTime);

            Instant currentSimulationTime = simulationStartTime;

            // --- Main Playback Loop ---
            while (!Thread.currentThread().isInterrupted()) {
                long loopWallClockStartNs = System.nanoTime();

                Instant windowStartTime = currentSimulationTime;
                Instant windowEndTime = windowStartTime.plus(POLLING_INTERVAL);

                logger.debug("Polling for events for {} in window [{}, {})", simulationId, windowStartTime,
                        windowEndTime);
                List<DomainEvent> externalEvents = new ArrayList<>();
                Instant physicalNow = timeService.physicalNow();
                Instant externalWindowEnd = windowEndTime.isAfter(physicalNow) ? physicalNow : windowEndTime;
                if (windowStartTime.isBefore(externalWindowEnd)) {
                    externalEvents.addAll(clickHouseService.getEventsBetween(windowStartTime, externalWindowEnd));
                }

                externalEvents.sort(Comparator.comparing(DomainEvent::getTimestamp));
                playWindow(simulationId, externalEvents, state, windowStartTime, windowEndTime);
                currentSimulationTime = windowEndTime;

                // CRITICAL: Persistently save the progress after every chunk.
                simulationService.checkpointSimulationAt(simulationId, currentSimulationTime);

                // --- Dynamic Sleep Calculation ---
                double currentSpeedFactor = state.getSpeedFactor();
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
                    simulationService.updateSimulationStatus(simulationId, SimulationStatus.STOPPED,
                            state.getLastProcessedTimestamp());
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
                simulationService.updateSimulationStatus(simulationId, SimulationStatus.FAILED,
                        state.getLastProcessedTimestamp());
            }
        }

        logger.info("Playback thread for simulation {} is terminating.", simulationId);
        return new AsyncResult<>(null);
    }

    /**
     * Plays one polling window while leaving future internal events in the queue
     * until they are due. ClickHouse events win timestamp ties so real history can
     * cancel or replace a queued internal event before it fires.
     */
    private void playWindow(String simulationId, List<DomainEvent> externalEvents, SimulationState state,
            Instant windowStartTime, Instant windowEndTime)
            throws InterruptedException {
        final long windowWallClockStartNs = System.nanoTime();
        int externalIndex = 0;

        while (externalIndex < externalEvents.size() || hasInternalEventDueAtOrBefore(state, windowEndTime)) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Playback cancelled during window processing.");
            }

            DomainEvent nextExternal = externalIndex < externalEvents.size() ? externalEvents.get(externalIndex)
                    : null;
            DomainEvent nextInternal = state.getInternalEventQueue().peek();

            if (nextExternal != null && shouldProcessExternalBeforeInternal(nextExternal, nextInternal,
                    windowEndTime)) {
                playEventAtScheduledTime(nextExternal, state, windowWallClockStartNs, windowStartTime);
                processEvent(simulationId, nextExternal);
                externalIndex++;
                continue;
            }

            DomainEvent internalEvent = state.getInternalEventQueue().poll();
            if (internalEvent == null) {
                break;
            }

            simulationService.markInternalEventProcessed(internalEvent);
            if (!internalEvent.getTimestamp().isBefore(windowStartTime)) {
                playEventAtScheduledTime(internalEvent, state, windowWallClockStartNs, windowStartTime);
            }
            processEvent(simulationId, internalEvent);
        }
    }

    private boolean hasInternalEventDueAtOrBefore(SimulationState state, Instant timestamp) {
        DomainEvent event = state.getInternalEventQueue().peek();
        return event != null && !event.getTimestamp().isAfter(timestamp);
    }

    private boolean shouldProcessExternalBeforeInternal(DomainEvent externalEvent, DomainEvent internalEvent,
            Instant windowEndTime) {
        if (internalEvent == null || internalEvent.getTimestamp().isAfter(windowEndTime)) {
            return true;
        }
        return !externalEvent.getTimestamp().isAfter(internalEvent.getTimestamp());
    }

    private void playEventAtScheduledTime(DomainEvent event, SimulationState state, long windowWallClockStartNs,
            Instant windowStartTime)
            throws InterruptedException {
        Duration simulationTimeElapsed = Duration.between(windowStartTime, event.getTimestamp());

        while (true) {
            double currentSpeedFactor = state.getSpeedFactor();
            long scheduledWallClockOffsetNs = (long) (simulationTimeElapsed.toNanos() / currentSpeedFactor);
            long actualWallClockOffsetNs = System.nanoTime() - windowWallClockStartNs;
            long waitNanos = scheduledWallClockOffsetNs - actualWallClockOffsetNs;

            if (waitNanos <= 0) {
                return;
            }

            long waitMillis = waitNanos / 1_000_000;
            int waitNanosRemainder = (int) (waitNanos % 1_000_000);

            synchronized (state.getTimingLock()) {
                state.getTimingLock().wait(waitMillis, waitNanosRemainder);
            }
        }
    }

    private void processEvent(String simulationId, DomainEvent event) {
        try {
            try (var timeContext = timeService.enterVirtualTime(event.getTimestamp())) {
                eventProcessor.processEvent(event);
            }
        } catch (Exception e) {
            logger.warn("Failed to process event {} for simulation {}", event, simulationId, e);
        }
    }
}
