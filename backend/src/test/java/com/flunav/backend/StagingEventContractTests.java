package com.flunav.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import flunav.events.ConnectionTypeChangedEvent;
import flunav.events.DomainEvent;
import flunav.events.ReleaseStagingConveyorEvent;
import flunav.types.ConveyorType;

class StagingEventContractTests {
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void stagingEventsRoundTripWithStableIdentityTypeAndTimestamp() throws Exception {
        Instant timestamp = Instant.parse("2026-09-12T09:30:00Z");
        ReleaseStagingConveyorEvent release = new ReleaseStagingConveyorEvent("staging-1", timestamp);
        ConnectionTypeChangedEvent typeChange = new ConnectionTypeChangedEvent(
                "staging-1", ConveyorType.STAGING, timestamp);

        DomainEvent restoredRelease = objectMapper.readValue(
                objectMapper.writeValueAsString(release), DomainEvent.class);
        DomainEvent restoredTypeChange = objectMapper.readValue(
                objectMapper.writeValueAsString(typeChange), DomainEvent.class);

        ReleaseStagingConveyorEvent typedRelease = assertInstanceOf(
                ReleaseStagingConveyorEvent.class, restoredRelease);
        ConnectionTypeChangedEvent typedChange = assertInstanceOf(
                ConnectionTypeChangedEvent.class, restoredTypeChange);
        assertEquals("RELEASE_STAGING_CONVEYOR", typedRelease.getEventType());
        assertEquals("CONNECTION_TYPE_CHANGED", typedChange.getEventType());
        assertEquals("staging-1", typedRelease.getEntityId());
        assertEquals("staging-1", typedChange.getEntityId());
        assertEquals(timestamp, typedRelease.getTimestamp());
        assertEquals(timestamp, typedChange.getTimestamp());
        assertEquals(ConveyorType.STAGING, typedChange.getConveyorType());
    }
}
