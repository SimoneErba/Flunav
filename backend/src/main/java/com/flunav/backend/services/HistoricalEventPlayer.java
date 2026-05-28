package com.flunav.backend.services;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Future;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.AsyncResult;
import org.springframework.stereotype.Service;

import com.flunav.backend.context.DatabaseContextHolder;
import com.flunav.backend.models.simulation.SimulationState;
import com.flunav.backend.models.simulation.SimulationStatus;

import flunav.events.DomainEvent;

@Service
public class HistoricalEventPlayer {

    private static final Logger logger = LoggerFactory.getLogger(HistoricalEventPlayer.class);
    private final EventProcessor eventProcessor;
    private final ClickHouseService clickHouseService;
    private final SimulationService simulationService;
    private final WebSocketService webSocketService;
    private final TimeService timeService;

    private static final Duration PLAYBACK_WINDOW = Duration.ofSeconds(5);
    private static final Duration EXTERNAL_LOOKAHEAD_WALL_TIME = Duration.ofSeconds(1);
    private static final Duration MAX_EXTERNAL_LOOKAHEAD_WINDOW = Duration.ofMinutes(1);

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
            Instant externalEventsLoadedThrough = simulationStartTime;
            List<DomainEvent> bufferedExternalEvents = new ArrayList<>();

            // --- Main Playback Loop ---
            while (!Thread.currentThread().isInterrupted()) {
                long loopWallClockStartNs = System.nanoTime();
                double currentSpeedFactor = state.getSpeedFactor();

                Instant windowStartTime = currentSimulationTime;
                Instant windowEndTime = windowStartTime.plus(PLAYBACK_WINDOW);

                logger.debug("Polling for events for {} in window [{}, {})", simulationId, windowStartTime,
                        windowEndTime);
                Instant physicalNow = timeService.physicalNow();
                externalEventsLoadedThrough = loadExternalEventsIntoBuffer(bufferedExternalEvents,
                        externalEventsLoadedThrough, windowStartTime, windowEndTime, physicalNow, currentSpeedFactor);

                List<DomainEvent> externalEvents = drainBufferedEventsThrough(bufferedExternalEvents, windowEndTime);
                externalEvents.sort(Comparator.comparing(DomainEvent::getTimestamp));
                playWindow(simulationId, externalEvents, state, windowStartTime, windowEndTime);
                waitUntilScheduledSimulationTime(state, loopWallClockStartNs, windowStartTime, windowEndTime);

                currentSimulationTime = windowEndTime;
                simulationService.checkpointSimulationAt(simulationId, currentSimulationTime);
                webSocketService.broadcastSimulationUpdate(simulationId, state.getStatus(), currentSimulationTime);
            }
        } catch (InterruptedException e) {
            handlePlaybackInterruption(simulationId);
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            if (isInterruptedFailure(e)) {
                Thread.interrupted();
                handlePlaybackInterruption(simulationId);
                Thread.currentThread().interrupt();
                logger.info("Playback thread for simulation {} is terminating.", simulationId);
                return new AsyncResult<>(null);
            }
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

    private void handlePlaybackInterruption(String simulationId) {
        SimulationState state = null;
        try {
            state = simulationService.getSimulationState(simulationId);
        } catch (Exception e) {
            logger.debug("Playback for simulation {} interrupted after state was removed.", simulationId);
        }

        if (simulationService.consumePlaybackRescheduleInterruption(simulationId)) {
            logger.info("Playback for simulation {} is being rescheduled at {}.", simulationId,
                    state != null ? state.getLastProcessedTimestamp() : null);
        } else if (state != null && state.getStatus() == SimulationStatus.PAUSED) {
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
    }

    private boolean isInterruptedFailure(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof InterruptedException) {
                return true;
            }
            current = current.getCause();
        }
        return Thread.currentThread().isInterrupted();
    }

    /**
     * Prefetches ClickHouse events far enough ahead that high-speed playback is not
     * forced to wait on one database query for every small wall-clock tick. Events
     * remain buffered until their simulation timestamp reaches the active playback
     * window.
     */
    private Instant loadExternalEventsIntoBuffer(List<DomainEvent> bufferedExternalEvents, Instant loadedThrough,
            Instant windowStartTime, Instant windowEndTime, Instant physicalNow, double speedFactor) {
        Instant targetEnd = windowStartTime.plus(externalLookaheadWindow(speedFactor));
        if (targetEnd.isBefore(windowEndTime)) {
            targetEnd = windowEndTime;
        }
        if (targetEnd.isAfter(physicalNow)) {
            targetEnd = physicalNow;
        }

        if (!loadedThrough.isBefore(targetEnd)) {
            return loadedThrough;
        }

        bufferedExternalEvents.addAll(clickHouseService.getEventsBetween(loadedThrough, targetEnd));
        bufferedExternalEvents.sort(Comparator.comparing(DomainEvent::getTimestamp));
        return targetEnd;
    }

    /**
     * Converts the current speed factor into a simulation-time prefetch window while
     * capping the query range so fast playback cannot request unbounded history.
     */
    private Duration externalLookaheadWindow(double speedFactor) {
        double lookaheadNanos = EXTERNAL_LOOKAHEAD_WALL_TIME.toNanos() * Math.max(1.0, speedFactor);
        long cappedLookaheadNanos = (long) Math.min(MAX_EXTERNAL_LOOKAHEAD_WINDOW.toNanos(), lookaheadNanos);
        return Duration.ofNanos(Math.max(PLAYBACK_WINDOW.toNanos(), cappedLookaheadNanos));
    }

    /**
     * Moves only events due in the current playback window out of the prefetch
     * buffer. Later events stay buffered so replay ordering remains timestamp based.
     */
    private List<DomainEvent> drainBufferedEventsThrough(List<DomainEvent> bufferedExternalEvents,
            Instant windowEndTime) {
        List<DomainEvent> externalEvents = new ArrayList<>();
        Iterator<DomainEvent> iterator = bufferedExternalEvents.iterator();
        while (iterator.hasNext()) {
            DomainEvent event = iterator.next();
            if (event.getTimestamp().isAfter(windowEndTime)) {
                break;
            }
            externalEvents.add(event);
            iterator.remove();
        }
        return externalEvents;
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
        waitUntilScheduledSimulationTime(state, windowWallClockStartNs, windowStartTime, event.getTimestamp());
    }

    private void waitUntilScheduledSimulationTime(SimulationState state, long windowWallClockStartNs,
            Instant windowStartTime, Instant targetSimulationTime)
            throws InterruptedException {
        Duration simulationTimeElapsed = Duration.between(windowStartTime, targetSimulationTime);

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
