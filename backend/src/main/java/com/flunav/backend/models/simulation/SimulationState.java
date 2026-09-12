package com.flunav.backend.models.simulation;

import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.PriorityBlockingQueue;

import flunav.events.DomainEvent;
import flunav.events.AnomalyEvaluationTickEvent;
import lombok.Data;

/**
 * In-process runtime state for one simulation.
 *
 * Lifecycle metadata is persisted to Redis by SimulationService. The event queue,
 * scheduled-event index, locks, and playback generation exist only in the owning
 * backend process and must never be replaced by a metadata refresh. Equal-time
 * movement events run before anomaly ticks; tick cadence then defines stable order.
 */
@Data
public class SimulationState {

    private final String id;
    private final Instant timestamp;
    private volatile SimulationStatus status;
    private volatile Instant lastHeartbeatTimestamp;
    private volatile Instant lastProcessedTimestamp;
    private volatile double speedFactor = 1.0;
    private volatile double buildProgress;
    private SimulationKind kind = SimulationKind.STANDARD;
    private String sourceSimulationId;
    private Instant forkTimestamp;
    private Instant liveHandoffTimestamp;
    private volatile LiveInputState liveInputState = LiveInputState.ACTIVE;
    private final Object executionLock = new Object();
    private final AtomicLong playbackGeneration = new AtomicLong();
    private final Object timingLock = new Object();
    private final PriorityBlockingQueue<DomainEvent> internalEventQueue = new PriorityBlockingQueue<>(11,
            Comparator.comparing(DomainEvent::getTimestamp)
                    .thenComparingInt(SimulationState::eventPriority)
                    .thenComparing(DomainEvent::getEventId));
    private final Map<String, DomainEvent> scheduledEventsByItem = new ConcurrentHashMap<>();

    public SimulationState(String id, Instant timestamp) {
        this(id, timestamp, SimulationStatus.QUEUED, Instant.now(), null, 1.0, 0.0);
    }

    public SimulationState(String id, Instant timestamp, SimulationStatus status, Instant lastHeartbeatTimestamp,
            Instant lastProcessedTimestamp, double speedFactor) {
        this(id, timestamp, status, lastHeartbeatTimestamp, lastProcessedTimestamp, speedFactor,
                status == SimulationStatus.READY ? 100.0 : 0.0);
    }

    public SimulationState(String id, Instant timestamp, SimulationStatus status, Instant lastHeartbeatTimestamp,
            Instant lastProcessedTimestamp, double speedFactor, double buildProgress) {
        this.id = id;
        this.timestamp = timestamp;
        this.status = status;
        this.lastHeartbeatTimestamp = lastHeartbeatTimestamp;
        this.lastProcessedTimestamp = lastProcessedTimestamp;
        this.speedFactor = speedFactor;
        this.buildProgress = buildProgress;
    }

    public synchronized void setSpeedFactor(double speedFactor) {
        this.speedFactor = speedFactor;
        // Playback waits on timingLock between clock steps; wake it so a new speed
        // takes effect without waiting for the previous delay.
        synchronized (this.timingLock) {
            this.timingLock.notifyAll();
        }
    }

    private static int eventPriority(DomainEvent event) {
        if (!(event instanceof AnomalyEvaluationTickEvent tick)) {
            return 0;
        }
        return switch (tick.getCadence()) {
            case FAST -> 1;
            case MINUTE -> 2;
            case BASELINE -> 3;
        };
    }
}
