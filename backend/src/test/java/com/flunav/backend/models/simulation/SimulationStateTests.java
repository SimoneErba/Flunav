package com.flunav.backend.models.simulation;

import flunav.events.ItemPositionChangedEvent;
import flunav.events.AnomalyEvaluationTickEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SimulationStateTests {

    @Test
    void internalEventQueuePollsByAscendingTimestamp() {
        Instant base = Instant.parse("2026-02-07T12:00:00Z");
        SimulationState state = new SimulationState("sim-queue", base);
        ItemPositionChangedEvent third = new ItemPositionChangedEvent("item-3", "conv-c", 0.0, base.plusSeconds(30));
        ItemPositionChangedEvent first = new ItemPositionChangedEvent("item-1", "conv-a", 0.0, base.plusSeconds(10));
        ItemPositionChangedEvent second = new ItemPositionChangedEvent("item-2", "conv-b", 0.0, base.plusSeconds(20));

        state.getInternalEventQueue().add(third);
        state.getInternalEventQueue().add(first);
        state.getInternalEventQueue().add(second);

        assertSame(first, state.getInternalEventQueue().poll());
        assertSame(second, state.getInternalEventQueue().poll());
        assertSame(third, state.getInternalEventQueue().poll());
    }

    @Test
    void movementAndDetectorTicksHaveStableBoundaryOrder() {
        Instant boundary = Instant.parse("2026-02-07T12:00:00Z");
        SimulationState state = new SimulationState("sim-boundary-order", boundary.minusSeconds(1));
        AnomalyEvaluationTickEvent baseline = new AnomalyEvaluationTickEvent(
                AnomalyEvaluationTickEvent.Cadence.BASELINE, boundary);
        AnomalyEvaluationTickEvent minute = new AnomalyEvaluationTickEvent(
                AnomalyEvaluationTickEvent.Cadence.MINUTE, boundary);
        AnomalyEvaluationTickEvent fast = new AnomalyEvaluationTickEvent(
                AnomalyEvaluationTickEvent.Cadence.FAST, boundary);
        ItemPositionChangedEvent movement = new ItemPositionChangedEvent("item", "conveyor", 1.0, boundary);

        state.getInternalEventQueue().add(baseline);
        state.getInternalEventQueue().add(minute);
        state.getInternalEventQueue().add(fast);
        state.getInternalEventQueue().add(movement);

        assertSame(movement, state.getInternalEventQueue().poll());
        assertSame(fast, state.getInternalEventQueue().poll());
        assertSame(minute, state.getInternalEventQueue().poll());
        assertSame(baseline, state.getInternalEventQueue().poll());
        assertEquals(0, state.getInternalEventQueue().size());
    }
}
