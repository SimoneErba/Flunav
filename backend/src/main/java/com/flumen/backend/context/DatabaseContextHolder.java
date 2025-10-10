package com.flumen.backend.context;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DatabaseContextHolder {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseContextHolder.class);
    
    // A ThreadLocal variable means that each thread will have its own, independent copy of this String.
    // Thread A can set it to "sim_123" while Thread B has it set to "sim_456".
    private static final ThreadLocal<String> simulationIdContext = new ThreadLocal<>();

    /**
     * Sets the simulation ID for the current thread. All subsequent DB calls on this thread
     * will be directed to this simulation's database.
     * @param simulationId The unique ID of the simulation database.
     */
    public static void setSimulationId(String simulationId) {
        logger.debug("Setting database context for thread [{}]: {}", Thread.currentThread().getName(), simulationId);
        simulationIdContext.set(simulationId);
    }

    /**
     * Gets the simulation ID for the current thread.
     * @return The simulation ID, or null if the context is for the live database.
     */
    public static String getSimulationId() {
        return simulationIdContext.get();
    }

    /**
     * CRITICAL: Clears the context for the current thread. This MUST be called in a
     * finally block to prevent memory leaks and state corruption in a thread-pooled environment.
     */
    public static void clear() {
        logger.debug("Clearing database context for thread [{}].", Thread.currentThread().getName());
        simulationIdContext.remove();
    }
}