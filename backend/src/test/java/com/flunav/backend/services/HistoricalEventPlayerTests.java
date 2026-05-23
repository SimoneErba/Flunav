package com.flunav.backend.services;

import com.flunav.backend.models.simulation.SimulationState;
import flunav.events.DomainEvent;
import flunav.events.EntityEvent;
import flunav.events.ItemPositionChangedEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HistoricalEventPlayerTests {

    @Test
    void clickHouseEventCanCancelLaterQueuedInternalEventBeforeItFires() throws Exception {
        String simulationId = "sim-order";
        Instant start = Instant.parse("2026-02-07T12:00:00Z");
        ItemPositionChangedEvent externalEvent = new ItemPositionChangedEvent("item-order", "replacement", 0.0,
                start.plusMillis(100));
        ItemPositionChangedEvent internalEvent = new ItemPositionChangedEvent("item-order", "stale", 100.0,
                start.plusMillis(200));
        SimulationState state = new SimulationState(simulationId, start);
        state.getInternalEventQueue().add(internalEvent);

        EventProcessor eventProcessor = mock(EventProcessor.class);
        ClickHouseService clickHouseService = mock(ClickHouseService.class);
        SimulationService simulationService = mock(SimulationService.class);
        WebSocketService webSocketService = mock(WebSocketService.class);
        TimeService timeService = new TimeService();
        timeService.useFixedClock(start.plusSeconds(10));
        HistoricalEventPlayer player = new HistoricalEventPlayer(eventProcessor, clickHouseService,
                simulationService, webSocketService, timeService);

        when(simulationService.getSimulationState(simulationId)).thenReturn(state);
        when(clickHouseService.getEventsBetween(any(Instant.class), any(Instant.class)))
                .thenReturn(List.of(externalEvent));

        AtomicBoolean internalProcessed = new AtomicBoolean(false);
        List<DomainEvent> processedEvents = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            DomainEvent event = invocation.getArgument(0);
            processedEvents.add(event);
            if (event == externalEvent) {
                state.getInternalEventQueue().removeIf(candidate -> candidate instanceof EntityEvent entityEvent
                        && entityEvent.getEntityId().equals("item-order"));
            }
            if (event == internalEvent) {
                internalProcessed.set(true);
            }
            return Map.of("status", "PROCESSED_SUCCESSFULLY");
        }).when(eventProcessor).processEvent(any(DomainEvent.class));

        doAnswer(invocation -> {
            Instant timestamp = invocation.getArgument(1);
            if (timestamp.isAfter(start)) {
                Thread.currentThread().interrupt();
            }
            return null;
        }).when(simulationService).updateLastProcessedTimestamp(eq(simulationId), any(Instant.class));

        var executor = Executors.newSingleThreadExecutor();
        try {
            var task = executor.submit(() -> player.playEvents(simulationId, start, 1000.0));
            task.get(2, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertFalse(internalProcessed.get(), "Canceled internal event must not be processed");
        assertEquals(List.of(externalEvent), processedEvents);
    }
}
