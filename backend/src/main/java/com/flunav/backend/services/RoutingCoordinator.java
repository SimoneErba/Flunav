package com.flunav.backend.services;

import com.flunav.backend.context.DatabaseContextHolder;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

@Service
public class RoutingCoordinator {
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    /**
     * Serializes capacity-sensitive routing within the current live or simulation
     * namespace. A per-context lock avoids cross-simulation blocking while keeping
     * concurrent arrivals from racing on the same occupancy and reservation data.
     */
    public <T> T withRoutingLock(Supplier<T> work) {
        String simulationId = DatabaseContextHolder.getSimulationId();
        String key = simulationId == null ? "live" : "sim:" + simulationId;
        ReentrantLock lock = locks.computeIfAbsent(key, ignored -> new ReentrantLock());
        lock.lock();
        try {
            return work.get();
        } finally {
            lock.unlock();
        }
    }
}
