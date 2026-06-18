package com.flunav.backend.services;

import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;

/**
 * Centralized service to manage time across the application.
 * The physical clock represents the real system timeline. Virtual time is scoped
 * to the current thread while simulation events are being processed.
 */
@Service
public class TimeService {
    private volatile Clock physicalClock = Clock.systemUTC();
    private final ThreadLocal<Instant> virtualNow = new ThreadLocal<>();

    /**
     * Returns domain time for event processing.
     * Simulation and replay code see scoped virtual time here, while live code falls
     * back to the physical clock.
     */
    public Instant now() {
        Instant scopedVirtualNow = virtualNow.get();
        return scopedVirtualNow != null ? scopedVirtualNow : physicalNow();
    }

    /**
     * Returns real wall-clock time regardless of virtual replay context.
     * Use this only for scheduling, cleanup, heartbeat, and boundaries against the
     * real world.
     */
    public Instant physicalNow() {
        return Instant.now(physicalClock);
    }

    public long millis() {
        Instant scopedVirtualNow = virtualNow.get();
        return scopedVirtualNow != null ? scopedVirtualNow.toEpochMilli() : physicalClock.millis();
    }

    /**
     * Replaces the physical clock with a fixed or adjustable one.
     * Primarily used for deterministic tests.
     */
    public void setClock(Clock clock) {
        this.physicalClock = Objects.requireNonNull(clock);
    }

    /**
     * Resets the physical clock to the system default and clears scoped virtual
     * time on the current thread.
     */
    public void reset() {
        this.physicalClock = Clock.systemUTC();
        this.virtualNow.remove();
    }

    /**
     * Runs the current thread on a simulation timestamp until the returned context
     * is closed.
     */
    public TimeContext enterVirtualTime(Instant instant) {
        return new TimeContext(instant);
    }

    /**
     * Utility to set the physical clock to a fixed instant.
     */
    public void useFixedClock(Instant instant) {
        this.physicalClock = Clock.fixed(instant, ZoneId.of("UTC"));
    }

    public final class TimeContext implements AutoCloseable {
        private final Instant previousVirtualNow;

        /**
         * Captures the previous virtual timestamp so nested replay contexts restore
         * the caller's time instead of clearing it accidentally.
         */
        private TimeContext(Instant instant) {
            this.previousVirtualNow = virtualNow.get();
            virtualNow.set(Objects.requireNonNull(instant));
        }

        @Override
        public void close() {
            if (previousVirtualNow == null) {
                virtualNow.remove();
            } else {
                virtualNow.set(previousVirtualNow);
            }
        }
    }
}
