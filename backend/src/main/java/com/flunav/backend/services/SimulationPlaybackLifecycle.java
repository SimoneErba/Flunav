package com.flunav.backend.services;

import java.time.Instant;
import java.util.concurrent.Future;
import com.flunav.backend.models.simulation.SimulationState;
import com.flunav.backend.models.simulation.SimulationStatus;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/** Owns playback transitions under the existing lifecycle and execution locks. */
@Component
final class SimulationPlaybackLifecycle {
    private final SimulationRuntimeState runtimeState;
    private final SimulationService simulations;
    private final WebSocketService webSocketService;
    private final HistoricalEventPlayer historicalEventPlayer;

    SimulationPlaybackLifecycle(SimulationRuntimeState runtimeState, @Lazy SimulationService simulations,
            WebSocketService webSocketService, @Lazy HistoricalEventPlayer historicalEventPlayer) {
        this.runtimeState = runtimeState;
        this.simulations = simulations;
        this.webSocketService = webSocketService;
        this.historicalEventPlayer = historicalEventPlayer;
    }

    void startPlayback(String simulationId, double speedFactor) {
        synchronized (runtimeState.lifecycleLock(simulationId)) {
            simulations.requireNoWhatIfChild(simulationId);
            validateSpeedFactor(speedFactor);
            SimulationState state = simulations.getSimulationState(simulationId);
            if (state.getStatus() != SimulationStatus.READY && state.getStatus() != SimulationStatus.STOPPED
                    && state.getStatus() != SimulationStatus.PAUSED) {
                throw new IllegalStateException("Simulation not ready for playback. Current status: " + state.getStatus());
            }
            Instant simulationStartTime = state.getLastProcessedTimestamp() != null ? state.getLastProcessedTimestamp()
                    : state.getTimestamp();
            startPlaybackWorker(simulationId, state, simulationStartTime, speedFactor);
        }
    }

    void startPlaybackWorker(String simulationId, SimulationState state, Instant simulationStartTime,
            double speedFactor) {
        simulations.initializeAnomalySchedule(simulationId, simulationStartTime);
        state.setStatus(SimulationStatus.PLAYING);
        state.setSpeedFactor(speedFactor);
        state.setLastProcessedTimestamp(simulationStartTime);
        runtimeState.persistState(state);
        webSocketService.broadcastSimulationUpdate(simulationId, SimulationStatus.PLAYING, simulationStartTime);
        long generation = state.getPlaybackGeneration().incrementAndGet();
        var playbackFuture = historicalEventPlayer.playEvents(simulationId, simulationStartTime, speedFactor, generation);
        runtimeState.activePlaybacks.put(simulationId, playbackFuture);
    }

    void cancelPlayback(String simulationId) {
        synchronized (runtimeState.lifecycleLock(simulationId)) {
            Future<?> playbackFuture = runtimeState.activePlaybacks.get(simulationId);
            if (playbackFuture == null || playbackFuture.isDone()) {
                runtimeState.activePlaybacks.remove(simulationId, playbackFuture);
                runtimeState.playbackCancellationReasons.remove(simulationId);
                return;
            }

            SimulationState state = runtimeState.simulationCache.get(simulationId);
            if (state != null) {
                synchronized (state.getExecutionLock()) {
                    state.setStatus(SimulationStatus.STOPPED);
                    state.getPlaybackGeneration().incrementAndGet();
                    runtimeState.persistState(state);
                }
            }
            cancelActivePlayback(simulationId, SimulationRuntimeState.PlaybackCancellationReason.STOP);
        }
    }

    void pauseSimulation(String simulationId) {
        synchronized (runtimeState.lifecycleLock(simulationId)) {
            SimulationState state = runtimeState.simulationCache.get(simulationId);
            Future<?> playbackTask = runtimeState.activePlaybacks.get(simulationId);
            if (state == null || playbackTask == null) {
                throw new IllegalStateException("Simulation " + simulationId + " does not exist or is not running.");
            }
            if (state.getStatus() != SimulationStatus.PLAYING) {
                throw new IllegalStateException(
                        "Simulation " + simulationId + " is not playing. Current state: " + state.getStatus());
            }
            synchronized (state.getExecutionLock()) {
                state.setStatus(SimulationStatus.PAUSED);
                state.getPlaybackGeneration().incrementAndGet();
                runtimeState.persistState(state);
                webSocketService.broadcastSimulationUpdate(simulationId, SimulationStatus.PAUSED,
                        state.getLastProcessedTimestamp());
                cancelActivePlayback(simulationId, SimulationRuntimeState.PlaybackCancellationReason.PAUSE);
            }
        }
    }

    void updatePlaybackSpeed(String simulationId, double newSpeedFactor) {
        synchronized (runtimeState.lifecycleLock(simulationId)) {
            validateSpeedFactor(newSpeedFactor);
            SimulationState state = simulations.getSimulationState(simulationId);
            state.setSpeedFactor(newSpeedFactor);
            runtimeState.persistState(state);
            webSocketService.broadcastSpeedUpdate(simulationId, newSpeedFactor, simulations.getSimulationClock(state));

            if (state.getStatus() != SimulationStatus.PLAYING) {
                return;
            }

            synchronized (state.getExecutionLock()) {
                Instant restartTimestamp = simulations.getSimulationClock(state);
                state.getPlaybackGeneration().incrementAndGet();
                cancelActivePlayback(simulationId, SimulationRuntimeState.PlaybackCancellationReason.RESCHEDULE);
                state.setStatus(SimulationStatus.PLAYING);
                state.setLastProcessedTimestamp(restartTimestamp);
                runtimeState.persistState(state);
                simulations.recalculateMovementSchedules(simulationId, restartTimestamp);
                startPlaybackWorker(simulationId, state, restartTimestamp, newSpeedFactor);
            }
        }
    }

    boolean consumePlaybackRescheduleInterruption(String simulationId) {
        return runtimeState.playbackCancellationReasons.remove(simulationId) == SimulationRuntimeState.PlaybackCancellationReason.RESCHEDULE;
    }

    private void validateSpeedFactor(double speedFactor) {
        if (!Double.isFinite(speedFactor) || speedFactor <= 0) {
            throw new IllegalArgumentException("Playback speed factor must be greater than zero.");
        }
    }

    private void cancelActivePlayback(String simulationId, SimulationRuntimeState.PlaybackCancellationReason reason) {
        Future<?> playbackFuture = runtimeState.activePlaybacks.remove(simulationId);
        if (playbackFuture != null && !playbackFuture.isDone()) {
            runtimeState.playbackCancellationReasons.put(simulationId, reason);
            playbackFuture.cancel(true);
        }
    }
}
