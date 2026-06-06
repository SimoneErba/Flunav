package com.flunav.backend.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import flunav.context.UserContextHolder;

class ContextMdcTests {

    @AfterEach
    void clearContexts() {
        DatabaseContextHolder.clearSimulation();
        UserContextHolder.clear();
        MDC.clear();
    }

    @Test
    void nestedSimulationContextsRestoreThreadLocalAndMdcValues() {
        try (var outer = DatabaseContextHolder.enterSimulationContext("outer-simulation")) {
            assertEquals("outer-simulation", DatabaseContextHolder.getSimulationId());
            assertEquals("outer-simulation", MDC.get("simulation_id"));

            try (var inner = DatabaseContextHolder.enterSimulationContext("inner-simulation")) {
                assertEquals("inner-simulation", DatabaseContextHolder.getSimulationId());
                assertEquals("inner-simulation", MDC.get("simulation_id"));
            }

            assertEquals("outer-simulation", DatabaseContextHolder.getSimulationId());
            assertEquals("outer-simulation", MDC.get("simulation_id"));
        }

        assertNull(DatabaseContextHolder.getSimulationId());
        assertNull(MDC.get("simulation_id"));
    }

    @Test
    void nestedSenderContextsRestoreThreadLocalAndMdcValues() {
        try (var outer = UserContextHolder.enterSenderContext("outer-sender")) {
            assertEquals("outer-sender", UserContextHolder.getSenderId());
            assertEquals("outer-sender", MDC.get("sender_id"));

            try (var inner = UserContextHolder.enterSenderContext("inner-sender")) {
                assertEquals("inner-sender", UserContextHolder.getSenderId());
                assertEquals("inner-sender", MDC.get("sender_id"));
            }

            assertEquals("outer-sender", UserContextHolder.getSenderId());
            assertEquals("outer-sender", MDC.get("sender_id"));
        }

        assertNull(UserContextHolder.getSenderId());
        assertNull(MDC.get("sender_id"));
    }

    @Test
    void eventMdcContextRestoresPreviousValues() {
        MDC.put("event_id", "outer-event");

        try (var ignored = MdcContext.withValues(java.util.Map.of(
                "event_id", "inner-event",
                "event_type", "ITEM_CREATED"))) {
            assertEquals("inner-event", MDC.get("event_id"));
            assertEquals("ITEM_CREATED", MDC.get("event_type"));
        }

        assertEquals("outer-event", MDC.get("event_id"));
        assertNull(MDC.get("event_type"));
    }
}
