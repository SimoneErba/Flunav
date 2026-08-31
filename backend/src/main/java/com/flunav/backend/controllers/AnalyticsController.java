package com.flunav.backend.controllers;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.server.ResponseStatusException;

import com.flunav.backend.models.analytics.EntityEventType;
import com.flunav.backend.models.response.EntityEventRecord;
import com.flunav.backend.models.response.ConveyorStopMetric;
import com.flunav.backend.models.response.JourneySummary;
import com.flunav.backend.models.response.ThroughputMetric;
import com.flunav.backend.context.DatabaseContextHolder;
import com.flunav.backend.services.ClickHouseService;
import com.flunav.backend.services.ThroughputBucketService;
import com.flunav.backend.services.OperationalAnalyticsService;
import com.flunav.backend.repositories.AnomalyObservationRepository;
import com.flunav.backend.models.analytics.AnomalyFinding;
import com.flunav.backend.models.analytics.AnomalyIncident;
import com.flunav.backend.models.analytics.DetectorBaseline;
import com.flunav.backend.services.AnomalyBackfillService;

@RestController
@RequestMapping("/api/analytics")
public class AnalyticsController {

    private final ClickHouseService clickHouseService;
    private final ThroughputBucketService throughputBucketService;
    private final OperationalAnalyticsService operationalAnalyticsService;
    private final AnomalyObservationRepository anomalyRepository;
    private final AnomalyBackfillService anomalyBackfillService;

    public AnalyticsController(
            ClickHouseService clickHouseService,
            ThroughputBucketService throughputBucketService,
            OperationalAnalyticsService operationalAnalyticsService,
            AnomalyObservationRepository anomalyRepository,
            AnomalyBackfillService anomalyBackfillService) {
        this.clickHouseService = clickHouseService;
        this.throughputBucketService = throughputBucketService;
        this.operationalAnalyticsService = operationalAnalyticsService;
        this.anomalyRepository = anomalyRepository;
        this.anomalyBackfillService = anomalyBackfillService;
    }

    @GetMapping("/anomalies")
    public List<AnomalyFinding> getAnomalies(
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to) {
        Instant effectiveTo = to != null ? to : operationalAnalyticsService.contextNow();
        Instant effectiveFrom = from != null ? from : effectiveTo.minusSeconds(24 * 3600L);
        if (effectiveFrom.isAfter(effectiveTo)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "from must be before to");
        }
        return anomalyRepository.getFindings(effectiveFrom, effectiveTo);
    }

    @GetMapping("/anomalies/{findingId}")
    public AnomalyFinding getAnomaly(@PathVariable String findingId) {
        AnomalyFinding finding = anomalyRepository.getFinding(findingId);
        if (finding == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Anomaly finding not found");
        }
        return finding;
    }

    @GetMapping("/anomaly-incidents")
    public List<AnomalyIncident> getAnomalyIncidents() {
        return anomalyRepository.getIncidents();
    }

    @GetMapping("/anomaly-incidents/{incidentId}")
    public AnomalyIncident getAnomalyIncident(@PathVariable String incidentId) {
        AnomalyIncident incident = anomalyRepository.getIncident(incidentId);
        if (incident == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Anomaly incident not found");
        }
        return incident;
    }

    @GetMapping("/transit-baselines")
    public List<DetectorBaseline> getTransitBaselines() {
        return anomalyRepository.getBaselines().stream()
                .filter(value -> value.detector() == com.flunav.backend.models.analytics.AnomalyDetectorType.TRANSIT_TIME_SLOW)
                .toList();
    }

    public record BackfillRequest(Instant from, Instant to) {
    }

    @PreAuthorize("hasAnyRole('ADMIN','SUPERADMIN')")
    @PostMapping("/anomaly-backfills")
    public ResponseEntity<AnomalyBackfillService.BackfillStatus> startAnomalyBackfill(
            @RequestBody BackfillRequest request) {
        try {
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(anomalyBackfillService.start(request.from(), request.to()));
        } catch (IllegalArgumentException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, error.getMessage());
        }
    }

    @PreAuthorize("hasAnyRole('ADMIN','SUPERADMIN')")
    @GetMapping("/anomaly-backfills/{jobId}")
    public AnomalyBackfillService.BackfillStatus getAnomalyBackfill(@PathVariable String jobId) {
        return anomalyBackfillService.status(jobId);
    }

    @GetMapping("/journeys/summary")
    public CompletableFuture<ResponseEntity<JourneySummary>> getJourneySummary(
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to) {
        TimeWindow window = operationalWindow(from, to);
        return operationalAnalyticsService.getJourneySummary(window.from(), window.to())
                .thenApply(ResponseEntity::ok);
    }

    @GetMapping("/conveyor-stops")
    public CompletableFuture<ResponseEntity<List<ConveyorStopMetric>>> getConveyorStops(
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to) {
        TimeWindow window = operationalWindow(from, to);
        return operationalAnalyticsService.getConveyorStops(window.from(), window.to())
                .thenApply(ResponseEntity::ok);
    }

    /**
     * Defaults analytics windows to the latest 24 hours in the active live or
     * simulation clock so paused historical views do not drift with wall time.
     */
    private TimeWindow operationalWindow(Instant from, Instant to) {
        Instant effectiveTo = to != null ? to : operationalAnalyticsService.contextNow();
        Instant effectiveFrom = from != null ? from : effectiveTo.minusSeconds(24 * 3600L);
        if (effectiveFrom.isAfter(effectiveTo)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "from must be before to");
        }
        return new TimeWindow(effectiveFrom, effectiveTo);
    }

    private record TimeWindow(Instant from, Instant to) {
    }

    @GetMapping("/throughput/history")
    public CompletableFuture<ResponseEntity<List<ThroughputMetric>>> getThroughputHistory(
            @RequestParam(defaultValue = "24") int hours,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "5") int bucketSeconds) {
        if (bucketSeconds <= 0 || bucketSeconds > 3600) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "bucketSeconds must be between 1 and 3600");
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "from must be before to");
        }

        Instant now = Instant.now();
        Instant effectiveTo = to != null ? to : now;
        Instant effectiveFrom = from != null
                ? from
                : effectiveTo.minusSeconds(Math.max(1, hours) * 3600L);
        if (effectiveFrom.isAfter(effectiveTo)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "from must be before to");
        }
        String simulationId = DatabaseContextHolder.getSimulationId();
        CompletableFuture<List<ThroughputMetric>> history = simulationId == null
                ? clickHouseService.getThroughputHistory(effectiveFrom, effectiveTo, bucketSeconds)
                : throughputBucketService.getSimulationHistory(
                        simulationId,
                        effectiveFrom,
                        effectiveTo,
                        bucketSeconds);

        return history
                .thenApply(ResponseEntity::ok);
    }

    @PreAuthorize("hasAnyRole('ADMIN','SUPERADMIN')")
    @GetMapping("/entity-events")
    public CompletableFuture<ResponseEntity<List<EntityEventRecord>>> getEntityEvents(
            @RequestParam EntityEventType entityType,
            @RequestParam String entityId,
            @RequestParam(defaultValue = "200") int limit) {
        String normalizedEntityId = entityId == null ? "" : entityId.trim();
        if (normalizedEntityId.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "entityId must not be blank");
        }

        int clampedLimit = Math.max(1, Math.min(limit, 1000));
        return clickHouseService.getEntityEvents(entityType, normalizedEntityId, clampedLimit)
                .thenApply(ResponseEntity::ok);
    }
}
