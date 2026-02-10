package com.flunav.backend.services;

import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

/**
 * Centralized service to manage time across the application.
 * This allows for deterministic testing by swapping the real clock with a simulation clock.
 */
@Service
public class TimeService {
    private Clock clock = Clock.systemUTC();

    public Instant now() {
        return Instant.now(clock);
    }

    public long millis() {
        return clock.millis();
    }

    /**
     * Replaces the current clock with a fixed or adjustable one.
     * Primarily used for testing and simulations.
     */
    public void setClock(Clock clock) {
        this.clock = clock;
    }

    /**
     * Resets the clock to the system default.
     */
    public void reset() {
        this.clock = Clock.systemUTC();
    }

    /**
     * Utility to jump forward in time if using a mutable clock.
     * Note: This requires a specialized Clock implementation if we want to "advance" 
     * without creating a new FixedClock every time.
     */
    public void useFixedClock(Instant instant) {
        this.clock = Clock.fixed(instant, ZoneId.of("UTC"));
    }
}
