package com.flunav.backend.services;

import java.time.Instant;
import java.util.concurrent.Future;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.AsyncResult;
import org.springframework.stereotype.Service;

import com.flunav.backend.context.DatabaseContextHolder;
import com.flunav.backend.context.AnomalyProcessingContext;
import com.flunav.backend.models.analytics.AnomalyProcessingMode;
import com.flunav.backend.models.simulation.EventOrigin;
import com.flunav.backend.models.simulation.LiveInputState;
import com.flunav.backend.models.simulation.SimulationKind;
import com.flunav.backend.models.simulation.SimulationState;
import com.flunav.backend.models.simulation.SimulationStatus;
import flunav.events.DomainEvent;

/**
 * Advances a ready simulation by merging external input and internal projections.
 *
 * External events win equal-timestamp ties. Once virtual time passes physical
 * time, live input is permanently frozen and only simulation-generated events can
 * advance the branch. Playback speed changes wall-clock delay, never event time.
 */
@Service
public class HistoricalEventPlayer {
    private static final Logger logger = LoggerFactory.getLogger(HistoricalEventPlayer.class);
    private final EventProcessor eventProcessor;
    private final ClickHouseService clickHouseService;
    private final SimulationService simulationService;
    private final SimulationInputService inputService;
    private final WebSocketService webSocketService;
    private final TimeService timeService;
    private final ObjectProvider<MetricSnapshotService> metricSnapshotService;

    public HistoricalEventPlayer(EventProcessor eventProcessor, ClickHouseService clickHouseService,
            @Lazy SimulationService simulationService, SimulationInputService inputService,
            WebSocketService webSocketService, TimeService timeService,
            ObjectProvider<MetricSnapshotService> metricSnapshotService) {
        this.eventProcessor = eventProcessor;
        this.clickHouseService = clickHouseService;
        this.simulationService = simulationService;
        this.inputService = inputService;
        this.webSocketService = webSocketService;
        this.timeService = timeService;
        this.metricSnapshotService = metricSnapshotService;
    }

    /**
     * History ends at a flushed handoff boundary. After it, committed live events
     * enter the same ordered queue directly, including while playback is paused.
     * Short clock steps allow new live arrivals to participate before movement is reduced.
     */
    @SuppressWarnings("deprecation")
    @Async("taskExecutor")
    public Future<Void> playEvents(String simulationId, Instant start, double speed, long generation) {
        SimulationState state = simulationService.getSimulationState(simulationId);
        try (var context = DatabaseContextHolder.enterSimulationContext(simulationId)) {
            if (!isCurrentPlayer(state, generation)) return new AsyncResult<>(null);
            simulationService.ensureLiveHandoff(state);
            Instant loadedThrough = start;
            Instant current = start;
            long wallStart = System.nanoTime();
            long lastBroadcast = wallStart;
            while (!Thread.currentThread().isInterrupted() && isCurrentPlayer(state, generation)) {
                long elapsed = System.nanoTime() - wallStart;
                Instant target = start.plusNanos((long) (elapsed * speed));
                if (target.isAfter(current.plusSeconds(5))) target = current.plusSeconds(5);
                Instant historyEnd = target.plusSeconds(60);
                if (state.getLiveHandoffTimestamp() != null && historyEnd.isAfter(state.getLiveHandoffTimestamp())) {
                    historyEnd = state.getLiveHandoffTimestamp();
                }
                if (state.getKind() == SimulationKind.STANDARD && state.getLiveInputState() == LiveInputState.ACTIVE
                        && loadedThrough.isBefore(historyEnd)) {
                    inputService.addHistory(simulationId, clickHouseService.getEventsBetween(loadedThrough, historyEnd));
                    loadedThrough = historyEnd;
                }
                synchronized (state.getExecutionLock()) {
                    if (!isCurrentPlayer(state, generation) || Thread.currentThread().isInterrupted()) break;
                    advanceThrough(state, target);
                    captureVirtualMetricBoundary(simulationId, current, target);
                    current = target;
                }
                if (System.nanoTime() - lastBroadcast >= 250_000_000L) {
                    webSocketService.broadcastSimulationUpdate(simulationId, state.getStatus(), current);
                    lastBroadcast = System.nanoTime();
                }
                synchronized (state.getTimingLock()) {
                    state.getTimingLock().wait(25);
                }
            }
        } catch (Exception failure) {
            if (state.getPlaybackGeneration().get() == generation && state.getStatus() == SimulationStatus.PLAYING) {
                logger.error("Playback failed for simulation {}", simulationId, failure);
                simulationService.updateSimulationStatus(simulationId, SimulationStatus.FAILED,
                        state.getLastProcessedTimestamp());
            }
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
        }
        return new AsyncResult<>(null);
    }

    private boolean isCurrentPlayer(SimulationState state, long generation) {
        return state.getStatus() == SimulationStatus.PLAYING && state.getPlaybackGeneration().get() == generation;
    }

    /** External events win timestamp ties; only branch movement survives the permanent future cutoff. */
    public void advanceThrough(SimulationState state, Instant target) {
        String id = state.getId();
        try (var context = DatabaseContextHolder.enterSimulationContext(id)) {
            while (true) {
                DomainEvent external = inputService.peek(id);
                DomainEvent internal = state.getInternalEventQueue().peek();
                boolean useExternal = external != null && (internal == null
                        || !external.getTimestamp().isAfter(internal.getTimestamp()));
                DomainEvent next = useExternal ? external : internal;
                if (next == null || next.getTimestamp().isAfter(target)) break;
                simulationService.freezeLiveInputIfFuture(id, next.getTimestamp());
                if (useExternal && state.getLiveInputState() == LiveInputState.FROZEN) continue;
                if (useExternal) inputService.poll(id);
                else {
                    state.getInternalEventQueue().poll();
                    simulationService.markInternalEventProcessed(next);
                }
                Instant eventClock = next.getTimestamp().isBefore(simulationService.getSimulationClock(state))
                        ? simulationService.getSimulationClock(state) : next.getTimestamp();
                simulationService.checkpointSimulationAt(id, eventClock);
                AnomalyProcessingMode mode = state.getLiveInputState() == LiveInputState.FROZEN
                        ? AnomalyProcessingMode.FUTURE_SIMULATION : AnomalyProcessingMode.HISTORICAL_PLAYBACK;
                try (var virtualTime = timeService.enterVirtualTime(eventClock);
                        var analytics = AnomalyProcessingContext.enter(mode)) {
                    eventProcessor.process(next, true, useExternal
                            ? EventOrigin.REPLAY : EventOrigin.SIMULATION_GENERATED).join();
                }
            }
            simulationService.checkpointSimulationAt(id, target);
        }
    }

    private void captureVirtualMetricBoundary(String id, Instant start, Instant end) {
        long boundarySeconds = Math.floorDiv(end.getEpochSecond(), 10) * 10;
        if (boundarySeconds <= start.getEpochSecond()) return;
        MetricSnapshotService snapshots = metricSnapshotService.getIfAvailable();
        if (snapshots == null) return;
        try (var virtualTime = timeService.enterVirtualTime(Instant.ofEpochSecond(boundarySeconds))) {
            snapshots.captureAt(id, Instant.ofEpochSecond(boundarySeconds));
        }
    }
}
