package com.flunav.backend.services;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.flunav.backend.context.AnomalyProcessingContext;
import com.flunav.backend.models.analytics.AnomalyProcessingMode;
import com.flunav.backend.repositories.AnomalyObservationRepository;

import flunav.events.AnomalyEvaluationTickEvent;

@Service
public class LiveAnomalyScheduler {
    private final AnomalyEngine engine;
    private final AnomalyObservationRepository observations;
    private final TimeService timeService;

    public LiveAnomalyScheduler(AnomalyEngine engine, AnomalyObservationRepository observations,
            TimeService timeService) {
        this.engine = engine;
        this.observations = observations;
        this.timeService = timeService;
    }

    /**
     * Recovers every elapsed physical boundary from persisted cadence watermarks.
     * Scheduler wake-up jitter therefore changes only when work begins, never the
     * timestamp or ordering of a detector evaluation.
     */
    @Scheduled(fixedRate = 1000)
    public synchronized void processDueBoundaries() {
        Instant now = timeService.physicalNow();
        List<AnomalyEvaluationTickEvent> due = new ArrayList<>();
        for (AnomalyEvaluationTickEvent.Cadence cadence : AnomalyEvaluationTickEvent.Cadence.values()) {
            long seconds = engine.cadenceSeconds(cadence);
            Instant currentAligned = Instant.ofEpochSecond(Math.floorDiv(now.getEpochSecond(), seconds) * seconds);
            Instant last = observations.getLastBoundary(cadence);
            Instant next = last != null ? engine.nextBoundary(last, cadence) : currentAligned;
            while (!next.isAfter(currentAligned)) {
                due.add(new AnomalyEvaluationTickEvent(cadence, next));
                next = engine.nextBoundary(next, cadence);
            }
        }
        due.sort(Comparator.comparing(AnomalyEvaluationTickEvent::getTimestamp)
                .thenComparingInt(tick -> tick.getCadence().ordinal()));
        try (var context = AnomalyProcessingContext.enter(AnomalyProcessingMode.LIVE)) {
            due.forEach(tick -> engine.evaluate(tick, AnomalyProcessingMode.LIVE));
        }
    }
}
