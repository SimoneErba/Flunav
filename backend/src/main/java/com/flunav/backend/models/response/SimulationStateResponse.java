package com.flunav.backend.models.response;

import java.time.Instant;

import com.flunav.backend.models.simulation.SimulationStatus;
import com.flunav.backend.models.simulation.SimulationState;

import lombok.Getter;

@Getter
public class SimulationStateResponse {

    private final String id;
    private final SimulationStatus status;
    private final Instant timestamp;
    private final Instant lastProcessedTimestamp;
    private final double speedFactor;
    private final double buildProgress;

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
    }

    /**
     * Builds the API response directly from the persisted simulation runtime state.
     */
    public SimulationStateResponse(SimulationState state) {
        this(state.getId(), state.getStatus(), state.getTimestamp(), state.getLastProcessedTimestamp(),
                state.getSpeedFactor(), state.getBuildProgress());
    }
}
