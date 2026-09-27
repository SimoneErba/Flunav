package com.flunav.backend.services;

import com.flunav.backend.repositories.LiveItemRepository;
import com.flunav.backend.repositories.LiveSimulationRepository;
import com.flunav.backend.models.simulation.SimulationStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Admission checks for simulation builds, independent of runtime queue ownership. */
@Component
final class SimulationCapacityManager {
    private final LiveItemRepository liveItemRepository;
    private final LiveSimulationRepository liveSimulationRepository;
    private final int maxActiveSimulations;
    private final int maxBuildingSimulations;
    private final long maxActiveItemsPerSimulation;
    private final long minFreeMemoryBytes;

    SimulationCapacityManager(LiveItemRepository liveItemRepository, LiveSimulationRepository liveSimulationRepository,
            @Value("${simulation.capacity.max-active:3}") int maxActiveSimulations,
            @Value("${simulation.capacity.max-building:1}") int maxBuildingSimulations,
            @Value("${simulation.capacity.max-active-items:10000}") long maxActiveItemsPerSimulation,
            @Value("${simulation.capacity.min-free-memory-bytes:536870912}") long minFreeMemoryBytes) {
        this.liveItemRepository = liveItemRepository;
        this.liveSimulationRepository = liveSimulationRepository;
        this.maxActiveSimulations = maxActiveSimulations;
        this.maxBuildingSimulations = maxBuildingSimulations;
        this.maxActiveItemsPerSimulation = maxActiveItemsPerSimulation;
        this.minFreeMemoryBytes = minFreeMemoryBytes;
    }

    void enforceAdmission() {
        SimulationCounts counts = countSimulations();
        if (maxActiveSimulations > 0 && counts.active() >= maxActiveSimulations) {
            reject("Maximum active simulations reached: " + maxActiveSimulations);
        }
        long liveActiveItems = liveItemRepository.countActiveItems(null);
        if (maxActiveItemsPerSimulation > 0 && liveActiveItems > maxActiveItemsPerSimulation) {
            reject("Active item count " + liveActiveItems + " exceeds simulation limit "
                    + maxActiveItemsPerSimulation);
        }
        if (minFreeMemoryBytes > 0 && availableHeapBytes() < minFreeMemoryBytes) {
            reject("Backend free heap is below the simulation admission threshold");
        }
    }

    void validateSourceItemCount(int itemCount) {
        if (maxActiveItemsPerSimulation > 0 && itemCount > maxActiveItemsPerSimulation) {
            reject("Source item count exceeds simulation limit " + maxActiveItemsPerSimulation);
        }
    }

    boolean hasBuildingCapacity() {
        return maxBuildingSimulations <= 0 || countSimulations().building() < maxBuildingSimulations;
    }

    private long availableHeapBytes() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory();
    }

    private SimulationCounts countSimulations() {
        int active = 0;
        int building = 0;
        for (var metadata : liveSimulationRepository.getAllSimulationStates()) {
            SimulationStatus status = metadata.status();
            if (status == SimulationStatus.BUILDING) building++;
            if (status != SimulationStatus.FAILED) active++;
        }
        return new SimulationCounts(active, building);
    }

    void reject(String reason) {
        throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, reason);
    }

    private record SimulationCounts(int active, int building) {}
}
