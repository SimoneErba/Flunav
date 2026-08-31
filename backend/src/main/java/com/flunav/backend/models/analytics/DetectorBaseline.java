package com.flunav.backend.models.analytics;

import java.time.Instant;

public record DetectorBaseline(
        String baselineId,
        String scopeId,
        String detectorVersion,
        AnomalyDetectorType detector,
        String componentId,
        String pathKey,
        Instant windowStart,
        Instant windowEnd,
        long sampleCount,
        double mean,
        double populationStddev,
        double median,
        double mad,
        double minimum,
        double maximum,
        Instant calculatedAt,
        Instant epochStartedAt) {
}
