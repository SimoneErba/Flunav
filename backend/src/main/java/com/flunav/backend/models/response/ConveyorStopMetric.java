package com.flunav.backend.models.response;

import java.time.Instant;

public record ConveyorStopMetric(
        String conveyorId,
        long overlappingStopCount,
        long totalStoppedMillis,
        double averageStoppedMillis,
        long maximumStoppedMillis,
        double availabilityPercentage,
        boolean currentlyStopped,
        Instant stopStartedAt) {
}
