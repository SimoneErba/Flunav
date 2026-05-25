package com.flunav.backend.services;

import com.flunav.backend.repositories.LiveItemRepository;
import com.flunav.backend.models.simulation.SimulationState;
import com.flunav.backend.models.simulation.SimulationStatus;
import flunav.events.DomainEvent;
import flunav.events.ItemPositionChangedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Semaphore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HistoricalGraphBuilderTests {

    @Mock
    private ClickHouseService clickHouseService;

    @Mock
    private EventProcessor eventProcessor;

    @Mock
    private OrientDBService orientDBService;

    @Mock
    private SimulationService simulationService;

    @Mock
    private LiveItemRepository liveItemRepository;

    private TimeService timeService;
    private HistoricalGraphBuilder builder;

    @BeforeEach
    void setUp() {
        timeService = new TimeService();
        builder = new HistoricalGraphBuilder(clickHouseService, eventProcessor, orientDBService, simulationService,
                liveItemRepository, timeService);

        doAnswer(invocation -> {
            OrientDBService.SessionCallback callback = invocation.getArgument(0);
            callback.execute(null);
            return null;
        }).when(orientDBService).withSession(any(OrientDBService.SessionCallback.class));
    }

    @Test
    void pastRestorePointReplaysClickHouseWithoutFutureProjection() {
        Instant physicalNow = Instant.parse("2026-02-07T12:00:00Z");
        Instant restorePoint = physicalNow.minusSeconds(30);
        SimulationState state = new SimulationState("sim-past", restorePoint);
        timeService.useFixedClock(physicalNow);
        when(simulationService.getSimulationState("sim-past")).thenReturn(state);
        when(clickHouseService.getMostRecentSnapshotBefore(restorePoint)).thenReturn(Optional.empty());
        when(clickHouseService.getEventsBetween(Instant.EPOCH, restorePoint)).thenReturn(List.of());

        builder.build("sim-past", restorePoint, new Semaphore(0));

        verify(clickHouseService).getMostRecentSnapshotBefore(restorePoint);
        verify(clickHouseService).getEventsBetween(Instant.EPOCH, restorePoint);
        verify(simulationService).checkpointSimulationAt("sim-past", restorePoint);
        verify(simulationService, never()).processEventsUntil("sim-past", restorePoint);
        verify(simulationService).updateSimulationStatus("sim-past", SimulationStatus.READY, restorePoint);
    }

    @Test
    void futureRestorePointProjectsInternalEventsAfterClickHouseReachesPhysicalNow() {
        Instant physicalNow = Instant.parse("2026-02-07T12:00:00Z");
        Instant restorePoint = physicalNow.plusSeconds(30);
        SimulationState state = new SimulationState("sim-future", restorePoint);
        timeService.useFixedClock(physicalNow);
        when(simulationService.getSimulationState("sim-future")).thenReturn(state);
        when(clickHouseService.getMostRecentSnapshotBefore(physicalNow)).thenReturn(Optional.empty());
        when(clickHouseService.getEventsBetween(Instant.EPOCH, physicalNow)).thenReturn(List.of());

        builder.build("sim-future", restorePoint, new Semaphore(0));

        verify(clickHouseService).getMostRecentSnapshotBefore(physicalNow);
        verify(clickHouseService).getEventsBetween(Instant.EPOCH, physicalNow);
        verify(simulationService).checkpointSimulationAt("sim-future", physicalNow);
        verify(simulationService).processEventsUntil("sim-future", restorePoint);
        verify(simulationService).updateSimulationStatus("sim-future", SimulationStatus.READY, restorePoint);
    }

    @Test
    void replayMergesClickHouseAndGeneratedInternalEventsByTimestamp() {
        Instant physicalNow = Instant.parse("2026-02-07T12:01:00Z");
        Instant restorePoint = Instant.parse("2026-02-07T12:00:30Z");
        Instant t0 = Instant.parse("2026-02-07T12:00:00Z");
        Instant t10 = Instant.parse("2026-02-07T12:00:10Z");
        Instant t20 = Instant.parse("2026-02-07T12:00:20Z");
        SimulationState state = new SimulationState("sim-merged", restorePoint);
        ItemPositionChangedEvent externalAtT0 = new ItemPositionChangedEvent("item-a", "conv-a", 0.0, t0);
        ItemPositionChangedEvent internalAtT10 = new ItemPositionChangedEvent("item-a", "conv-b", 50.0, t10);
        ItemPositionChangedEvent externalAtT20 = new ItemPositionChangedEvent("item-b", "conv-c", 0.0, t20);
        List<DomainEvent> processed = new ArrayList<>();

        timeService.useFixedClock(physicalNow);
        when(simulationService.getSimulationState("sim-merged")).thenReturn(state);
        when(clickHouseService.getMostRecentSnapshotBefore(restorePoint)).thenReturn(Optional.empty());
        when(clickHouseService.getEventsBetween(Instant.EPOCH, restorePoint))
                .thenReturn(List.of(externalAtT20, externalAtT0));
        doAnswer(invocation -> {
            DomainEvent event = invocation.getArgument(0);
            processed.add(event);
            if (event == externalAtT0) {
                state.getInternalEventQueue().add(internalAtT10);
            }
            return null;
        }).when(eventProcessor).processEventWithoutBroadcast(any(DomainEvent.class));
        doAnswer(invocation -> {
            DomainEvent event = state.getInternalEventQueue().poll();
            if (event != null) {
                processed.add(event);
            }
            return event;
        }).when(simulationService).processNextInternalEvent("sim-merged");

        builder.build("sim-merged", restorePoint, new Semaphore(0));

        assertEquals(List.of(externalAtT0, internalAtT10, externalAtT20), processed);
        verify(simulationService).checkpointSimulationAt("sim-merged", restorePoint);
    }

    @Test
    void clickHouseEventWinsTimestampTieAndCanCancelQueuedInternalEvent() {
        Instant physicalNow = Instant.parse("2026-02-07T12:01:00Z");
        Instant restorePoint = Instant.parse("2026-02-07T12:00:30Z");
        Instant timestamp = Instant.parse("2026-02-07T12:00:10Z");
        SimulationState state = new SimulationState("sim-tie", restorePoint);
        ItemPositionChangedEvent externalEvent = new ItemPositionChangedEvent("item-tie", "replacement", 0.0,
                timestamp);
        ItemPositionChangedEvent internalEvent = new ItemPositionChangedEvent("item-tie", "stale", 100.0,
                timestamp);
        List<DomainEvent> processed = new ArrayList<>();
        state.getInternalEventQueue().add(internalEvent);

        timeService.useFixedClock(physicalNow);
        when(simulationService.getSimulationState("sim-tie")).thenReturn(state);
        when(clickHouseService.getMostRecentSnapshotBefore(restorePoint)).thenReturn(Optional.empty());
        when(clickHouseService.getEventsBetween(Instant.EPOCH, restorePoint)).thenReturn(List.of(externalEvent));
        doAnswer(invocation -> {
            DomainEvent event = invocation.getArgument(0);
            processed.add(event);
            state.getInternalEventQueue().remove(internalEvent);
            return null;
        }).when(eventProcessor).processEventWithoutBroadcast(any(DomainEvent.class));

        builder.build("sim-tie", restorePoint, new Semaphore(0));

        assertEquals(List.of(externalEvent), processed);
        verify(simulationService, never()).processNextInternalEvent("sim-tie");
    }

    @Test
    void pastRestorePointDrainsDueInternalEventsAndLeavesFutureEventsQueued() {
        Instant physicalNow = Instant.parse("2026-02-07T12:01:00Z");
        Instant restorePoint = Instant.parse("2026-02-07T12:00:30Z");
        ItemPositionChangedEvent dueInternal = new ItemPositionChangedEvent("item-due", "conv-a", 50.0,
                Instant.parse("2026-02-07T12:00:10Z"));
        ItemPositionChangedEvent futureInternal = new ItemPositionChangedEvent("item-future", "conv-b", 50.0,
                Instant.parse("2026-02-07T12:00:40Z"));
        SimulationState state = new SimulationState("sim-drain", restorePoint);
        List<DomainEvent> processed = new ArrayList<>();
        state.getInternalEventQueue().add(futureInternal);
        state.getInternalEventQueue().add(dueInternal);

        timeService.useFixedClock(physicalNow);
        when(simulationService.getSimulationState("sim-drain")).thenReturn(state);
        when(clickHouseService.getMostRecentSnapshotBefore(restorePoint)).thenReturn(Optional.empty());
        when(clickHouseService.getEventsBetween(Instant.EPOCH, restorePoint)).thenReturn(List.of());
        doAnswer(invocation -> {
            DomainEvent event = state.getInternalEventQueue().poll();
            if (event != null) {
                processed.add(event);
            }
            return event;
        }).when(simulationService).processNextInternalEvent("sim-drain");

        builder.build("sim-drain", restorePoint, new Semaphore(0));

        assertEquals(List.of(dueInternal), processed);
        assertSame(futureInternal, state.getInternalEventQueue().peek());
    }

    @Test
    void futureRestorePointMergesUntilPhysicalNowThenProjectsRemainingInternalEvents() {
        Instant physicalNow = Instant.parse("2026-02-07T12:00:20Z");
        Instant restorePoint = Instant.parse("2026-02-07T12:00:40Z");
        ItemPositionChangedEvent externalEvent = new ItemPositionChangedEvent("item-future", "conv-a", 0.0,
                Instant.parse("2026-02-07T12:00:00Z"));
        ItemPositionChangedEvent internalEvent = new ItemPositionChangedEvent("item-future", "conv-b", 50.0,
                Instant.parse("2026-02-07T12:00:10Z"));
        SimulationState state = new SimulationState("sim-future-merge", restorePoint);
        List<DomainEvent> processed = new ArrayList<>();

        timeService.useFixedClock(physicalNow);
        when(simulationService.getSimulationState("sim-future-merge")).thenReturn(state);
        when(clickHouseService.getMostRecentSnapshotBefore(physicalNow)).thenReturn(Optional.empty());
        when(clickHouseService.getEventsBetween(Instant.EPOCH, physicalNow)).thenReturn(List.of(externalEvent));
        doAnswer(invocation -> {
            DomainEvent event = invocation.getArgument(0);
            processed.add(event);
            state.getInternalEventQueue().add(internalEvent);
            return null;
        }).when(eventProcessor).processEventWithoutBroadcast(any(DomainEvent.class));
        doAnswer(invocation -> {
            DomainEvent event = state.getInternalEventQueue().poll();
            if (event != null) {
                processed.add(event);
            }
            return event;
        }).when(simulationService).processNextInternalEvent("sim-future-merge");

        builder.build("sim-future-merge", restorePoint, new Semaphore(0));

        assertEquals(List.of(externalEvent, internalEvent), processed);
        verify(simulationService).checkpointSimulationAt("sim-future-merge", physicalNow);
        verify(simulationService).processEventsUntil("sim-future-merge", restorePoint);
    }

}
