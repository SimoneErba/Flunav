package com.flunav.backend.context;

import java.util.HashMap;
import java.util.Map;

/** Enables short-lived read caches only on a simulation build or multi-run worker thread. */
public final class SimulationBuildCacheContext implements AutoCloseable {
    public interface TopologyBypass extends AutoCloseable {
        @Override void close();
    }
    private static final ThreadLocal<SimulationBuildCacheContext> CURRENT = new ThreadLocal<>();

    private final SimulationBuildCacheContext previous;
    private final String simulationId;
    private final boolean generatedMultiRun;
    private boolean bypassTopology;
    private Object topologySnapshot;
    private final Map<String, Object> localItemMetadata = new HashMap<>();

    private SimulationBuildCacheContext(String simulationId, boolean generatedMultiRun) {
        previous = CURRENT.get();
        this.simulationId = simulationId;
        this.generatedMultiRun = generatedMultiRun;
        CURRENT.set(this);
    }

    public static SimulationBuildCacheContext enter(String simulationId) {
        return new SimulationBuildCacheContext(simulationId, false);
    }

    public static SimulationBuildCacheContext enterMultiRun(String simulationId) {
        return new SimulationBuildCacheContext(simulationId, true);
    }

    public static boolean enabled() {
        SimulationBuildCacheContext context = CURRENT.get();
        return context != null && context.simulationId.equals(DatabaseContextHolder.getSimulationId());
    }

    public static boolean generatedMultiRun() {
        return enabled() && CURRENT.get().generatedMultiRun;
    }

    public static Object localItemMetadata(String itemId) {
        return generatedMultiRun() ? CURRENT.get().localItemMetadata.get(itemId) : null;
    }

    public static void cacheLocalItemMetadata(String itemId, Object metadata) {
        if (generatedMultiRun()) {
            CURRENT.get().localItemMetadata.put(itemId, metadata);
        }
    }

    public static void evictLocalItemMetadata(String itemId) {
        if (generatedMultiRun()) {
            CURRENT.get().localItemMetadata.remove(itemId);
        }
    }

    public static boolean topologyBypassed() {
        return enabled() && CURRENT.get().bypassTopology;
    }

    public static Object topologySnapshot() {
        return enabled() ? CURRENT.get().topologySnapshot : null;
    }

    public static void setTopologySnapshot(Object snapshot) {
        if (enabled()) {
            CURRENT.get().topologySnapshot = snapshot;
        }
    }

    public static void invalidateTopology() {
        if (enabled()) {
            CURRENT.get().topologySnapshot = null;
        }
    }

    public static TopologyBypass bypassTopology() {
        SimulationBuildCacheContext context = CURRENT.get();
        if (context == null || !enabled()) {
            return () -> {};
        }
        boolean previousValue = context.bypassTopology;
        context.bypassTopology = true;
        return () -> context.bypassTopology = previousValue;
    }

    @Override
    public void close() {
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }
}
