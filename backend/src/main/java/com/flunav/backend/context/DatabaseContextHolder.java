package com.flunav.backend.context;

import com.orientechnologies.orient.core.db.ODatabaseSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Manages thread-local state for database operations.
 */
public final class DatabaseContextHolder {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseContextHolder.class);
    private static final String SIMULATION_ID_MDC_KEY = "simulation_id";

    private static final ThreadLocal<String> simulationIdContext = new ThreadLocal<>();
    private static final ThreadLocal<ODatabaseSession> transactionalSessionContext = new ThreadLocal<>();

    private DatabaseContextHolder() {
    }

    /**
     * An AutoCloseable resource that manages the lifecycle of the simulation
     * context.
     * Supports nested contexts by restoring the previous one.
     */
    public static class SimulationContext implements AutoCloseable {
        private final String previousSimulationId;
        private final String previousMdcSimulationId;

        private SimulationContext(String simulationId) {
            this.previousSimulationId = simulationIdContext.get();
            this.previousMdcSimulationId = MDC.get(SIMULATION_ID_MDC_KEY);
            logger.trace("Entering simulation context: {} (previous: {})", simulationId, previousSimulationId);
            setSimulationId(simulationId);
        }

        @Override
        public void close() {
            if (previousSimulationId == null) {
                logger.trace("Exiting simulation context, clearing thread-local.");
                simulationIdContext.remove();
            } else {
                logger.trace("Exiting simulation context, restoring previous: {}", previousSimulationId);
                simulationIdContext.set(previousSimulationId);
            }
            restoreMdc(previousMdcSimulationId);
        }
    }

    public static SimulationContext enterSimulationContext(String simulationId) {
        return new SimulationContext(simulationId);
    }

    public static String getSimulationId() {
        String sysProp = System.getProperty("simulation.id");
        if (sysProp != null)
            return sysProp;

        return simulationIdContext.get();
    }

    public static void clearSimulation() {
        simulationIdContext.remove();
        restoreMdc(System.getProperty("simulation.id"));
    }

    private static void setSimulationId(String simulationId) {
        if (simulationId == null) {
            simulationIdContext.remove();
            MDC.remove(SIMULATION_ID_MDC_KEY);
            return;
        }

        simulationIdContext.set(simulationId);
        MDC.put(SIMULATION_ID_MDC_KEY, simulationId);
    }

    private static void restoreMdc(String simulationId) {
        if (simulationId == null) {
            MDC.remove(SIMULATION_ID_MDC_KEY);
        } else {
            MDC.put(SIMULATION_ID_MDC_KEY, simulationId);
        }
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
