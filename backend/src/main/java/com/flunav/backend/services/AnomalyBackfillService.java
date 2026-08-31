package com.flunav.backend.services;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import com.flunav.backend.models.simulation.SimulationStatus;

@Service
public class AnomalyBackfillService {
    private final SimulationService simulations;
    private final Map<String, BackfillWindow> windows = new ConcurrentHashMap<>();

    public AnomalyBackfillService(SimulationService simulations) {
        this.simulations = simulations;
    }

    /**
     * Uses a deterministic isolated simulation id so retries and worker restarts
     * resume the same virtual build rather than writing duplicate findings.
     */
    public BackfillStatus start(Instant from, Instant to) {
        if (from == null || to == null || !from.isBefore(to)) {
            throw new IllegalArgumentException("from must be before to");
        }
        String jobId = "backfill_" + UUID.nameUUIDFromBytes((from + "|" + to)
                .getBytes(StandardCharsets.UTF_8)).toString().replace("-", "");
        windows.putIfAbsent(jobId, new BackfillWindow(from, to));
        simulations.getOrCreateSimulation(jobId, to);
        return status(jobId);
    }

    public BackfillStatus status(String jobId) {
        BackfillWindow window = windows.get(jobId);
        var state = simulations.getSimulationState(jobId);
        return new BackfillStatus(jobId, window != null ? window.from() : null,
                window != null ? window.to() : state.getTimestamp(), state.getStatus().name(),
                state.getBuildProgress(), state.getLastProcessedTimestamp(),
                state.getStatus() == SimulationStatus.READY);
    }

    private record BackfillWindow(Instant from, Instant to) {
    }

    public record BackfillStatus(String jobId, Instant from, Instant to, String status,
            double progress, Instant lastProcessedTimestamp, boolean complete) {
    }
}
