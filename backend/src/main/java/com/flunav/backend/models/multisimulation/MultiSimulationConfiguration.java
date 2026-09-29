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
        Instant simulationStartTime,
        boolean includeActiveItems,
        String inputGeneratorVersion) {

    public MultiSimulationConfiguration(String name, long simulationDurationSeconds, int numberOfRuns,
            ArrivalConfiguration arrival, String sourceLocationId, List<DestinationProbability> destinations,
            List<ConveyorFailureConfiguration> conveyorFailures, Long baseSeed, Instant simulationStartTime,
            boolean includeActiveItems) {
        this(name, simulationDurationSeconds, numberOfRuns, arrival, sourceLocationId, destinations,
                conveyorFailures, baseSeed, simulationStartTime, includeActiveItems, null);
    }

    public MultiSimulationConfiguration(String name, long simulationDurationSeconds, int numberOfRuns,
            ArrivalConfiguration arrival, String sourceLocationId, List<DestinationProbability> destinations,
            List<ConveyorFailureConfiguration> conveyorFailures, Long baseSeed, Instant simulationStartTime) {
        this(name, simulationDurationSeconds, numberOfRuns, arrival, sourceLocationId, destinations,
                conveyorFailures, baseSeed, simulationStartTime, false);
    }

    public MultiSimulationConfiguration {
        destinations = destinations == null ? List.of() : List.copyOf(destinations);
        conveyorFailures = conveyorFailures == null ? List.of() : List.copyOf(conveyorFailures);
    }
}
