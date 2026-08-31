package com.flunav.backend.models.response;

import java.time.Instant;

import com.flunav.backend.models.simulation.SimulationStatus;
import com.flunav.backend.models.simulation.SimulationState;
import flunav.events.AnomalyEvaluationTickEvent;

import lombok.Getter;

@Getter
public class SimulationStateResponse {

    private final String id;
    private final SimulationStatus status;
    private final Instant timestamp;
    private final Instant lastProcessedTimestamp;
    private final double speedFactor;
    private final double buildProgress;
    private final Instant nextFastTick;
    private final Instant nextMinuteTick;
    private final Instant nextBaselineTick;

    /**
     * Keeps existing call sites compatible when only the original simulation anchor
     * timestamp is available.
     */
    public SimulationStateResponse(String id, SimulationStatus status, Instant timestamp) {
        this(id, status, timestamp, null, 1.0, status == SimulationStatus.READY ? 100.0 : 0.0);
    }

    /**
     * Includes playback progress so clients can anchor their local simulation clock
     * to the backend's processed timestamp.
     */
    public SimulationStateResponse(String id, SimulationStatus status, Instant timestamp, Instant lastProcessedTimestamp,
            double speedFactor) {
        this(id, status, timestamp, lastProcessedTimestamp, speedFactor,
                status == SimulationStatus.READY ? 100.0 : 0.0);
    }

    public SimulationStateResponse(String id, SimulationStatus status, Instant timestamp, Instant lastProcessedTimestamp,
            double speedFactor, double buildProgress) {
        this.id = id;
        this.status = status;
        this.timestamp = timestamp;
        this.lastProcessedTimestamp = lastProcessedTimestamp;
        this.speedFactor = speedFactor;
        this.buildProgress = buildProgress;
        this.nextFastTick = null;
        this.nextMinuteTick = null;
        this.nextBaselineTick = null;
    }

    /**
     * Builds the API response directly from the persisted simulation runtime state.
     */
    public SimulationStateResponse(SimulationState state) {
        this.id = state.getId();
        this.status = state.getStatus();
        this.timestamp = state.getTimestamp();
        this.lastProcessedTimestamp = state.getLastProcessedTimestamp();
        this.speedFactor = state.getSpeedFactor();
        this.buildProgress = state.getBuildProgress();
        this.nextFastTick = nextTick(state, AnomalyEvaluationTickEvent.Cadence.FAST);
        this.nextMinuteTick = nextTick(state, AnomalyEvaluationTickEvent.Cadence.MINUTE);
        this.nextBaselineTick = nextTick(state, AnomalyEvaluationTickEvent.Cadence.BASELINE);
    }

    private static Instant nextTick(SimulationState state, AnomalyEvaluationTickEvent.Cadence cadence) {
        return state.getInternalEventQueue().stream()
                .filter(event -> event instanceof AnomalyEvaluationTickEvent tick && tick.getCadence() == cadence)
                .map(event -> event.getTimestamp())
                .min(Instant::compareTo)
                .orElse(null);
    }
}
