package com.flunav.backend.models.analytics;

import java.time.Instant;

import flunav.types.PositionType;

public record PositionTransitionObservation(
        String sourceEventId,
        String itemId,
        String previousPositionId,
        PositionType previousPositionType,
        Double previousProgress,
        String newPositionId,
        PositionType newPositionType,
        double newProgress,
        Instant timestamp) {
}
