package com.flunav.backend.context;

import com.orientechnologies.orient.core.db.ODatabaseSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages thread-local state for database operations.
 */
public final class DatabaseContextHolder {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseContextHolder.class);

    private static final ThreadLocal<String> simulationIdContext = new ThreadLocal<>();
    private static final ThreadLocal<ODatabaseSession> transactionalSessionContext = new ThreadLocal<>();

    private DatabaseContextHolder() {
    }

    /**
     * An AutoCloseable resource that manages the lifecycle of the simulation context.
     * Supports nested contexts by restoring the previous one.
     */
    public static class SimulationContext implements AutoCloseable {
        private final String previousSimulationId;

        private SimulationContext(String simulationId) {
            this.previousSimulationId = simulationIdContext.get();
            logger.debug("Entering simulation context: {} (previous: {})", simulationId, previousSimulationId);
            simulationIdContext.set(simulationId);
        }

        @Override
        public void close() {
            if (previousSimulationId == null) {
                logger.debug("Exiting simulation context, clearing thread-local.");
                simulationIdContext.remove();
            } else {
                logger.debug("Exiting simulation context, restoring previous: {}", previousSimulationId);
                simulationIdContext.set(previousSimulationId);
            }
        }
    }

    public static SimulationContext enterSimulationContext(String simulationId) {
        return new SimulationContext(simulationId);
    }

    public static String getSimulationId() {
        String sysProp = System.getProperty("simulation.id");
        if (sysProp != null) return sysProp;
        
        return simulationIdContext.get();
    }

    public static void clearSimulation() {
        simulationIdContext.remove();
    }

    public static class TransactionContext implements AutoCloseable {
        private TransactionContext(ODatabaseSession session) {
            transactionalSessionContext.set(session);
        }

        @Override
        public void close() {
            transactionalSessionContext.remove();
        }
    }

    public static TransactionContext enterTransactionContext(ODatabaseSession session) {
        return new TransactionContext(session);
    }

    public static ODatabaseSession getTransactionalSession() {
        return transactionalSessionContext.get();
    }

    public static void setTransactionalSession(ODatabaseSession session) {
        transactionalSessionContext.set(session);
    }

    public static void clearTransaction() {
        transactionalSessionContext.remove();
    }
}
