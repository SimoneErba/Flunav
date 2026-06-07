package com.flunav.backend.services;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SimulationBuildProgressTests {

    @Test
    void calculatesProgressFromReplayTimeRange() {
        Instant snapshotTimestamp = Instant.parse("2026-06-07T07:00:00Z");
        Instant restoreTimestamp = snapshotTimestamp.plusSeconds(3600);

        assertEquals(0, HistoricalGraphBuilder.calculateBuildProgress(
                snapshotTimestamp, restoreTimestamp, snapshotTimestamp.minusSeconds(1)));
        assertEquals(50, HistoricalGraphBuilder.calculateBuildProgress(
                snapshotTimestamp, restoreTimestamp, snapshotTimestamp.plusSeconds(1800)));
        assertEquals(100, HistoricalGraphBuilder.calculateBuildProgress(
                snapshotTimestamp, restoreTimestamp, restoreTimestamp.plusSeconds(1)));
    }

    @Test
    void completesZeroLengthReplayRange() {
        Instant timestamp = Instant.parse("2026-06-07T09:00:00Z");

        assertEquals(100, HistoricalGraphBuilder.calculateBuildProgress(timestamp, timestamp, timestamp));
    }
}
