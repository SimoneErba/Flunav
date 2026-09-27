package com.flunav.backend.services;

import com.flunav.backend.models.simulation.SimulationState;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

/** Owns the process-local simulation cache, playback handles, and build queue. */
@Component
final class SimulationRuntimeState {
    enum PlaybackCancellationReason { STOP, PAUSE, RESCHEDULE }

    final Map<String, SimulationState> simulationCache = new ConcurrentHashMap<>();
    final Map<String, Future<?>> activePlaybacks = new ConcurrentHashMap<>();
    final Map<String, PlaybackCancellationReason> playbackCancellationReasons = new ConcurrentHashMap<>();
    final Queue<SimulationService.SimulationRequest> waitingQueue = new ConcurrentLinkedQueue<>();
    final Semaphore buildPermits;
    private final Object[] lifecycleLocks = new Object[256];

    SimulationRuntimeState(@Value("${simulation.capacity.max-building:1}") int maxBuildingSimulations) {
        buildPermits = new Semaphore(maxBuildingSimulations > 0 ? maxBuildingSimulations : Integer.MAX_VALUE);
        for (int index = 0; index < lifecycleLocks.length; index++) {
            lifecycleLocks[index] = new Object();
        }
    }

    Object lifecycleLock(String simulationId) {
        return lifecycleLocks[Math.floorMod(simulationId.hashCode(), lifecycleLocks.length)];
    }
}
