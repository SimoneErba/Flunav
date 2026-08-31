package com.flunav.backend.services;

import java.time.Duration;
import java.util.List;

import org.springframework.stereotype.Service;

import com.flunav.backend.context.DatabaseContextHolder;
import com.flunav.backend.domain.Conveyor;
import com.flunav.backend.models.RedisLiveItem;
import com.flunav.backend.models.analytics.LocationFlowObservation;
import com.flunav.backend.models.analytics.PositionTransitionObservation;
import com.flunav.backend.models.analytics.TransitObservation;
import com.flunav.backend.models.analytics.LocationTransitMetric;
import com.flunav.backend.repositories.AnomalyObservationRepository;

import flunav.events.ConnectionCreatedEvent;
import flunav.events.ConnectionLengthChangedEvent;
import flunav.events.ConnectionPropertiesUpdatedEvent;
import flunav.events.ConnectionSpeedChangedEvent;
import flunav.events.DomainEvent;
import flunav.events.ItemPositionChangedEvent;
import flunav.types.PositionType;

@Service
public class AnomalyObservationService {
    private final AnomalyObservationRepository observations;
    private final TopologyProvider topology;
    private final ClickHouseService clickHouse;

    public AnomalyObservationService(AnomalyObservationRepository observations, TopologyProvider topology,
            ClickHouseService clickHouse) {
        this.observations = observations;
        this.topology = topology;
        this.clickHouse = clickHouse;
    }

    /**
     * Appends movement facts only after the position reducer has succeeded. No
     * detector or alarm logic runs on the event-processing thread.
     */
    public void collectPositionChange(ItemPositionChangedEvent event, RedisLiveItem previous,
            PositionType newType) {
        Double previousProgress = progress(previous);
        observations.appendTransition(new PositionTransitionObservation(event.getEventId(), event.getEntityId(),
                previous != null ? previous.getPositionId() : null,
                previous != null ? previous.getType() : null,
                previousProgress,
                event.getLocationId(), newType, event.getProgress(), event.getTimestamp()));

        if (previous != null && previous.getType() == PositionType.LOCATION && previous.getPositionId() != null) {
            appendFlow(new LocationFlowObservation(event.getEventId(), event.getEntityId(), previous.getPositionId(),
                    LocationFlowObservation.Direction.DEPARTURE, event.getTimestamp()));
        }
        if (newType == PositionType.LOCATION) {
            appendFlow(new LocationFlowObservation(event.getEventId(), event.getEntityId(), event.getLocationId(),
                    LocationFlowObservation.Direction.ARRIVAL, event.getTimestamp()));
        }
        collectTransit(event, previous, newType);
    }

    public void collectTopologyChange(DomainEvent event) {
        String conveyorId = switch (event) {
            case ConnectionCreatedEvent value -> value.getConnectionId();
            case ConnectionSpeedChangedEvent value -> value.getEntityId();
            case ConnectionLengthChangedEvent value -> value.getEntityId();
            case ConnectionPropertiesUpdatedEvent value -> value.getEntityId();
            default -> null;
        };
        if (conveyorId != null) {
            observations.setTimingEpoch(conveyorId, event.getTimestamp());
        }
    }

    private void collectTransit(ItemPositionChangedEvent event, RedisLiveItem previous, PositionType newType) {
        if (previous == null || previous.getType() != PositionType.CONVEYOR || previous.getEntryTime() == null) {
            return;
        }
        if (newType == PositionType.CONVEYOR && previous.getPositionId().equals(event.getLocationId())) {
            return;
        }
        Conveyor conveyor;
        try {
            conveyor = topology.getConveyorById(previous.getPositionId());
        } catch (RuntimeException missing) {
            return;
        }
        long duration = Duration.between(previous.getEntryTime(), event.getTimestamp()).toMillis();
        if (duration < 0 || conveyor == null) {
            return;
        }
        TransitObservation observation = new TransitObservation(event.getEventId(), event.getEntityId(),
                conveyor.getId(), conveyor.getSourceLocationId(), conveyor.getTargetLocationId(), duration,
                event.getTimestamp());
        observations.appendTransit(observation);
        clickHouse.saveLocationTransitMetricAsync(new LocationTransitMetric(event.getEventId(), event.getTimestamp(),
                scopeId(), event.getEntityId(), conveyor.getSourceLocationId(), conveyor.getTargetLocationId(),
                conveyor.getId(), PositionType.CONVEYOR, event.getLocationId(), newType, duration,
                List.of(conveyor.getSourceLocationId(), conveyor.getTargetLocationId())));
    }

    private void appendFlow(LocationFlowObservation observation) {
        observations.appendFlow(observation);
        clickHouse.saveComponentFlowObservation(observation, scopeId(), DatabaseContextHolder.getSimulationId());
    }

    private Double progress(RedisLiveItem item) {
        if (item == null || item.getType() != PositionType.CONVEYOR || item.getPositionId() == null) {
            return null;
        }
        try {
            Conveyor conveyor = topology.getConveyorById(item.getPositionId());
            return conveyor != null && conveyor.getLength() != null && conveyor.getLength() > 0
                    ? item.getAccumulatedDistance() / conveyor.getLength()
                    : null;
        } catch (RuntimeException missing) {
            return null;
        }
    }

    private String scopeId() {
        String simulationId = DatabaseContextHolder.getSimulationId();
        return simulationId != null ? simulationId : "live";
    }
}
