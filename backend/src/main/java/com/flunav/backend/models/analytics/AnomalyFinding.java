package com.flunav.backend.models.analytics;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import flunav.types.AlarmSeverity;
import flunav.types.ComponentType;

public record AnomalyFinding(
        String findingId,
        String scopeId,
        String simulationId,
        AnomalyDetectorType detector,
        String detectorVersion,
        AnomalyProcessingMode temporalMode,
        String componentId,
        ComponentType componentType,
        String itemId,
        String previousPositionId,
        String reportedPositionId,
        List<String> expectedIntermediatePositions,
        Map<String, Object> observedMetrics,
        Double baselineMean,
        Double baselineMedian,
        Double baselineStddev,
        Double baselineMad,
        Double zScore,
        Double modifiedZScore,
        long sampleCount,
        Instant baselineWindowStart,
        Instant baselineWindowEnd,
        AlarmSeverity severity,
        String alarmId,
        AlarmPromotionState alarmState,
        Instant observationTimestamp,
        Instant tickTimestamp) {
}
