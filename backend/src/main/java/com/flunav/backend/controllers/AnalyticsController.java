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

@RestController
@RequestMapping("/api/analytics")
public class AnalyticsController {

    private final ClickHouseService clickHouseService;
    private final ThroughputBucketService throughputBucketService;
    private final OperationalAnalyticsService operationalAnalyticsService;

    public AnalyticsController(
            ClickHouseService clickHouseService,
            ThroughputBucketService throughputBucketService,
            OperationalAnalyticsService operationalAnalyticsService) {
        this.clickHouseService = clickHouseService;
        this.throughputBucketService = throughputBucketService;
        this.operationalAnalyticsService = operationalAnalyticsService;
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
