package com.flunav.backend.services;

import com.flunav.backend.models.simulation.SimulationState;
import com.flunav.backend.models.simulation.SimulationKind;
import com.flunav.backend.repositories.LiveSimulationRepository;
import com.flunav.backend.repositories.LiveSimulationRepository.SimulationMetadata;
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

    private final LiveSimulationRepository liveSimulationRepository;

    final Map<String, SimulationState> simulationCache = new ConcurrentHashMap<>();
    final Map<String, Future<?>> activePlaybacks = new ConcurrentHashMap<>();
    final Map<String, PlaybackCancellationReason> playbackCancellationReasons = new ConcurrentHashMap<>();
    final Queue<SimulationService.SimulationRequest> waitingQueue = new ConcurrentLinkedQueue<>();
    final Semaphore buildPermits;
    private final Object[] lifecycleLocks = new Object[256];

    SimulationRuntimeState(@Value("${simulation.capacity.max-building:1}") int maxBuildingSimulations,
            LiveSimulationRepository liveSimulationRepository) {
        this.liveSimulationRepository = liveSimulationRepository;
        buildPermits = new Semaphore(maxBuildingSimulations > 0 ? maxBuildingSimulations : Integer.MAX_VALUE);
        for (int index = 0; index < lifecycleLocks.length; index++) {
            lifecycleLocks[index] = new Object();
        }
    }

    Object lifecycleLock(String simulationId) {
        return lifecycleLocks[Math.floorMod(simulationId.hashCode(), lifecycleLocks.length)];
    }

    /** Redis recovers metadata only; an existing runtime retains its queue and virtual clock. */
    SimulationState loadOrRefreshSimulationState(String simulationId) {
        SimulationState cached = simulationCache.get(simulationId);
        // Redis contains lifecycle metadata, not the in-memory event queue. Once this
        // process owns a state, refreshing it would discard work or rewind its clock.
        if (cached != null) {
            return cached;
        }
        SimulationMetadata metadata = liveSimulationRepository.getState(simulationId).orElse(null);

        if (metadata == null) {
            return cached;
        }

        SimulationState restored = new SimulationState(metadata.simulationId(), metadata.timestamp(), metadata.status(),
                metadata.lastHeartbeatTimestamp(), metadata.lastProcessedTimestamp(), metadata.speedFactor(),
                metadata.buildProgress());
        restoreBranchMetadata(restored, metadata);
        SimulationState existing = simulationCache.putIfAbsent(simulationId, restored);
        return existing != null ? existing : restored;
    }

    void persistState(SimulationState state) {
        if (state.getKind() == SimulationKind.MULTI_SIMULATION_RUN) {
            return;
        }
        liveSimulationRepository.saveState(new SimulationMetadata(
                state.getId(),
                state.getTimestamp(),
                state.getStatus(),
                state.getLastHeartbeatTimestamp(),
                state.getLastProcessedTimestamp(),
                state.getSpeedFactor(),
                state.getBuildProgress(), state.getKind(), state.getSourceSimulationId(), state.getForkTimestamp(),
                state.getLiveHandoffTimestamp(), state.getLiveInputState()));
    }

    void restoreBranchMetadata(SimulationState state, SimulationMetadata metadata) {
        state.setKind(metadata.kind());
        state.setSourceSimulationId(metadata.sourceSimulationId());
        state.setForkTimestamp(metadata.forkTimestamp());
        state.setLiveHandoffTimestamp(metadata.liveHandoffTimestamp());
        state.setLiveInputState(metadata.liveInputState());
    }
}
