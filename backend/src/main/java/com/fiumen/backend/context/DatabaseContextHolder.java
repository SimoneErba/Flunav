package com.fiumen.backend.context;

import com.orientechnologies.orient.core.db.ODatabaseSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages thread-local state for database operations.
 * This utility class provides a safe way to handle database sessions for both
 * live and simulation environments, especially in a multi-threaded context like
 * async tasks or web requests.
 */
public final class DatabaseContextHolder {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseContextHolder.class);

    private static final ThreadLocal<String> simulationIdContext = new ThreadLocal<>();
    private static final ThreadLocal<ODatabaseSession> transactionalSessionContext = new ThreadLocal<>();

    /**
     * Private constructor to prevent instantiation of this utility class.
     */
    private DatabaseContextHolder() {
    }

    // ===================================================================================
    // Simulation Context Management (using AutoCloseable)
    // ===================================================================================

    /**
     * An AutoCloseable resource that manages the lifecycle of the simulation
     * context.
     * Use with a try-with-resources statement for guaranteed cleanup.
     */
    public static class SimulationContext implements AutoCloseable {
        private SimulationContext(String simulationId) {
            logger.debug("Entering simulation context for thread [{}]: {}", Thread.currentThread().getName(),
                    simulationId);
            simulationIdContext.set(simulationId);
        }

        @Override
        public void close() {
            logger.debug("Exiting simulation context for thread [{}].", Thread.currentThread().getName());
            simulationIdContext.remove();
        }
    }

    /**
     * Establishes a simulation context for the current thread for the duration of a
     * try-with-resources block.
     *
     * @param simulationId The unique ID of the simulation database.
     * @return An AutoCloseable context object that will clear the context upon
     *         closing.
     */
    public static SimulationContext enterSimulationContext(String simulationId) {
        return new SimulationContext(simulationId);
    }

    /**
     * Gets the simulation ID for the current thread.
     * 
     * @return The simulation ID, or null if the context is for the live database.
     */
    public static String getSimulationId() {
        return simulationIdContext.get();
    }

    public static void clearSimulation() {
        simulationIdContext.remove();
    }

    // ===================================================================================
    // Transactional Session Management (Both AutoCloseable and Manual)
    // ===================================================================================

    /**
     * An AutoCloseable resource that manages the lifecycle of a transactional
     * session.
     * Use with a try-with-resources statement for guaranteed cleanup.
     */
    public static class TransactionContext implements AutoCloseable {
        private TransactionContext(ODatabaseSession session) {
            logger.debug("Entering transaction context for thread [{}].", Thread.currentThread().getName());
            transactionalSessionContext.set(session);
        }

        @Override
        public void close() {
            logger.debug("Exiting transaction context for thread [{}].", Thread.currentThread().getName());
            transactionalSessionContext.remove();
        }
    }

    /**
     * Establishes a transactional session for the current thread for the duration
     * of a
     * try-with-resources block. This is the recommended approach for new code.
     *
     * @param session The OrientDB session to be used for the transaction.
     * @return An AutoCloseable context object that will clear the session upon
     *         closing.
     */
    public static TransactionContext enterTransactionContext(ODatabaseSession session) {
        return new TransactionContext(session);
    }

    /**
     * Gets the transactional session for the current thread.
     * 
     * @return The session, or null if no transaction is active on this thread.
     */
    public static ODatabaseSession getTransactionalSession() {
        return transactionalSessionContext.get();
    }

    /**
     * Manually sets the transactional session.
     * NOTE: If you use this, you are responsible for calling clearTransaction() in
     * a finally block.
     * Prefer using enterTransactionContext for safer, automatic cleanup.
     *
     * @param session The OrientDB session.
     */
    public static void setTransactionalSession(ODatabaseSession session) {
        transactionalSessionContext.set(session);
    }

    /**
     * Manually clears the transactional session for the current thread.
     * CRITICAL: This must be called in a finally block if you used
     * setTransactionalSession().
     */
    public static void clearTransaction() {
        transactionalSessionContext.remove();
    }
}