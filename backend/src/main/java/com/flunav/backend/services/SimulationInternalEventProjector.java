package com.flunav.backend.services;

import com.flunav.backend.context.DatabaseContextHolder;
import com.flunav.backend.context.SimulationBuildCacheContext;
import com.flunav.backend.models.simulation.SimulationState;
import com.flunav.backend.models.simulation.SimulationStatus;
import com.flunav.backend.models.simulation.SimulationKind;
import flunav.events.DomainEvent;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/** Applies internal events in scoped simulation storage and virtual time, without live history writes. */
@Component
final class SimulationInternalEventProjector {
    private final SimulationRuntimeState runtimeState;
    private final EventProcessor eventProcessor;
    private final TimeService timeService;

    SimulationInternalEventProjector(SimulationRuntimeState runtimeState,
            @Lazy EventProcessor eventProcessor, TimeService timeService) {
        this.runtimeState = runtimeState;
        this.eventProcessor = eventProcessor;
        this.timeService = timeService;
    }

    void process(String simulationId, SimulationState state, DomainEvent event) {
        state.setLastProcessedTimestamp(event.getTimestamp());
        // Builds publish their Redis metadata through progress updates and their
        // final checkpoint. Persisting each internal tick adds a round trip while
        // the queue and current clock remain owned by this worker.
        boolean buildingWithProgressUpdates = state.getStatus() == SimulationStatus.BUILDING
                && SimulationBuildCacheContext.enabled();
        if (state.getKind() != SimulationKind.MULTI_SIMULATION_RUN && !buildingWithProgressUpdates) {
            runtimeState.persistState(state);
        }

        if (event instanceof flunav.events.EntityEvent ee) {
            state.getScheduledEventsByItem().remove(ee.getEntityId(), event);
        }

        try (var ctx = DatabaseContextHolder.enterSimulationContext(simulationId);
                var timeContext = timeService.enterVirtualTime(event.getTimestamp())) {
            eventProcessor.processEventWithoutBroadcast(event);
        }
    }
}
