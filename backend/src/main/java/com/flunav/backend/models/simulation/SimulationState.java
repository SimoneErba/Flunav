package com.flunav.backend.models.simulation;

import java.time.Instant;

import lombok.Data;

@Data
public class SimulationState {

    private final String id;
    private final Instant timestamp;
    private volatile SimulationStatus status;
    private Instant lastHeartbeatTimestamp;
    private volatile Instant lastProcessedTimestamp;
    private double speedFactor = 1.0;
    private final Object timingLock = new Object();

    public SimulationState(String id, Instant timestamp) {
        this.id = id;
        this.timestamp = timestamp;
        this.status = SimulationStatus.QUEUED;
        this.lastHeartbeatTimestamp = Instant.now();
        this.speedFactor = 1.0;
    }

    public synchronized void setSpeedFactor(double speedFactor) {
        this.speedFactor = speedFactor;
        // Wake up the player thread in case it's sleeping
        synchronized (this.timingLock) {
            this.timingLock.notifyAll();
        }
    }
}