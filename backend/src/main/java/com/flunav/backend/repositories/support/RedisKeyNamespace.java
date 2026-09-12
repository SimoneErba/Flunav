package com.flunav.backend.repositories.support;

import com.flunav.backend.context.DatabaseContextHolder;

/**
 * Builds Redis keys for the live namespace or one isolated simulation namespace.
 *
 * Services should call {@link #current(String)} for normal context-aware access.
 * Cleanup code that deliberately operates outside the target context can call
 * {@link #simulation(String, String)} with an explicit simulation id.
 */
public final class RedisKeyNamespace {

    private static final String SIMULATION_PREFIX = "sim:";

    private RedisKeyNamespace() {
    }

    public static String current(String baseKey) {
        return simulation(DatabaseContextHolder.getSimulationId(), baseKey);
    }

    public static String simulation(String simulationId, String baseKey) {
        return simulationId == null ? baseKey : SIMULATION_PREFIX + simulationId + ":" + baseKey;
    }
}
