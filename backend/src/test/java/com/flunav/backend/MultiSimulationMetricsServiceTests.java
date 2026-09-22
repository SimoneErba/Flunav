package com.flunav.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.flunav.backend.context.DatabaseContextHolder;
import com.flunav.backend.models.RedisLiveItem;
import com.flunav.backend.services.MultiSimulationMetricsService;

import flunav.events.ConnectionActivatedEvent;
import flunav.events.ConnectionDeactivatedEvent;
import flunav.events.ItemCreatedEvent;
import flunav.types.PositionType;

class MultiSimulationMetricsServiceTests {

    @Test
    void recordsJourneyDestinationFailuresAndVirtualDowntime() {
        MultiSimulationMetricsService service = new MultiSimulationMetricsService();
        Instant start = Instant.parse("2030-01-01T00:00:00Z");
        String simulationId = "metrics-run";
        service.start(simulationId);

        try (var ignored = DatabaseContextHolder.enterSimulationContext(simulationId)) {
            service.recordSuccessfulReduction(new ItemCreatedEvent(
                    "item", "Item", 1.0, 0.0, true, "source", PositionType.LOCATION, 0.0,
                    List.of("A"), Map.of(), start.plusSeconds(10)), Map.of("status", "CREATED"));
            service.recordSuccessfulReduction(
                    new ConnectionDeactivatedEvent("conveyor", start.plusSeconds(20)), Map.of());
            service.recordSuccessfulReduction(
                    new ConnectionActivatedEvent("conveyor", start.plusSeconds(50)), Map.of());
            service.recordSuccessfulExit(start.plusSeconds(70), RedisLiveItem.builder()
                    .id("item").createdAt(start.plusSeconds(10)).destinations(List.of("A")).build());
        }

        var metrics = service.finish(simulationId, 100, start.plusSeconds(100));
        assertEquals(1, metrics.itemsGenerated());
        assertEquals(1, metrics.itemsCompleted());
        assertEquals(60.0, metrics.averageJourneyTimeSeconds());
        assertEquals(1, metrics.failuresByConveyor().get("conveyor"));
        assertEquals(30.0, metrics.conveyorDowntimePercent().get("conveyor"));
        assertEquals(1, metrics.completedByDestination().get("A"));
    }
}
