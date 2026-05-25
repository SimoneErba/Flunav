package com.flunav.backend.models.simulation;

import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentHashMap;

import flunav.events.DomainEvent;
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
    private final PriorityQueue<DomainEvent> internalEventQueue = new PriorityQueue<>(
            Comparator.comparing(DomainEvent::getTimestamp).thenComparing(DomainEvent::getEventId));
    private final Map<String, DomainEvent> scheduledEventsByItem = new ConcurrentHashMap<>();

    public SimulationState(String id, Instant timestamp) {
        this(id, timestamp, SimulationStatus.QUEUED, Instant.now(), null, 1.0);
    }

    public SimulationState(String id, Instant timestamp, SimulationStatus status, Instant lastHeartbeatTimestamp,
            Instant lastProcessedTimestamp, double speedFactor) {
        this.id = id;
        this.timestamp = timestamp;
        this.status = status;
        this.lastHeartbeatTimestamp = lastHeartbeatTimestamp;
        this.lastProcessedTimestamp = lastProcessedTimestamp;
        this.speedFactor = speedFactor;
    }

    public synchronized void setSpeedFactor(double speedFactor) {
        this.speedFactor = speedFactor;
        // Wake up the player thread in case it's sleeping
        synchronized (this.timingLock) {
            this.timingLock.notifyAll();
        }
    }
}
