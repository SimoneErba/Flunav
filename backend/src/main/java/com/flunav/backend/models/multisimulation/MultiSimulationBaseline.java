package com.flunav.backend.models.multisimulation;

import java.time.Instant;
import java.util.List;

import com.flunav.backend.models.graph.GraphData;

import flunav.events.DomainEvent;

public record MultiSimulationBaseline(
        GraphData graph,
        List<DomainEvent> configurationEvents,
        Instant capturedAt,
        String topologyVersion,
        String configurationVersion) {

    public MultiSimulationBaseline {
        configurationEvents = configurationEvents == null ? List.of() : List.copyOf(configurationEvents);
    }
}
