package com.flunav.backend.models.multisimulation;

import java.time.Instant;
import java.util.List;

public record MultiSimulationConfiguration(
        String name,
        long simulationDurationSeconds,
        int numberOfRuns,
        ArrivalConfiguration arrival,
        String sourceLocationId,
        List<DestinationProbability> destinations,
        List<ConveyorFailureConfiguration> conveyorFailures,
        Long baseSeed,
        Instant simulationStartTime) {

    public MultiSimulationConfiguration {
        destinations = destinations == null ? List.of() : List.copyOf(destinations);
        conveyorFailures = conveyorFailures == null ? List.of() : List.copyOf(conveyorFailures);
    }
}
