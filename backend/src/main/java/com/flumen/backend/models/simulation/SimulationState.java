package com.flumen.backend.models.simulation;

import java.time.Instant;

import lombok.Data
;

@Data
public class SimulationState {

    private final String id;
    private final Instant timestamp;
    private SimulationStatus status;
    private Instant lastHeartbeatTimestamp;

    public SimulationState(String id, Instant timestamp) {
        this.id = id;
        this.timestamp = timestamp;
        this.status = SimulationStatus.QUEUED;
        this.lastHeartbeatTimestamp = Instant.now();
    }
}