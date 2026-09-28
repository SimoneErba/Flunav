package com.flunav.backend.services;

import com.flunav.backend.domain.Conveyor;
import com.flunav.backend.domain.Location;
import com.flunav.backend.context.AnomalyProcessingContext;
import com.flunav.backend.models.analytics.AnomalyProcessingMode;
import com.flunav.backend.models.UpdateModel;
import flunav.events.*;
import flunav.types.ActiveAlarm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import com.flunav.backend.context.DatabaseContextHolder;

/** Domain reduction helpers; invoked only inside EventProcessor context and retry boundaries. */
@Service
final class AlarmReducer {
    private static final Logger logger = LoggerFactory.getLogger(AlarmReducer.class);
    private final ConveyorService conveyorService;
    private final WebSocketService webSocketService;
    private final SimulationService simulationService;
    private final TimeService timeService;
    private final boolean manageLogic;
    private final LocationService locationService;
    private final AnomalyEngine anomalyEngine;
    private final EventReductionSupport support;

    AlarmReducer(
            ConveyorService conveyorService,
            WebSocketService webSocketService,
            @Lazy SimulationService simulationService,
            TimeService timeService,
            @Value("${simulation.manage-logic:true}") boolean manageLogic,
            LocationService locationService,
            @Lazy AnomalyEngine anomalyEngine,
            EventReductionSupport support) {
        this.conveyorService = conveyorService;
        this.webSocketService = webSocketService;
        this.simulationService = simulationService;
        this.timeService = timeService;
        this.manageLogic = manageLogic;
        this.locationService = locationService;
        this.anomalyEngine = anomalyEngine;
        this.support = support;
    }

    /** Applies derived state in the caller's context; history persistence stays in EventProcessor. */
    Map<String, Object> reduce(DomainEvent event, boolean shouldBroadcast) {
        return switch (event) {
            case AlarmRaisedEvent e -> {
                var conveyor = conveyorService.getConveyorById(e.getConveyorId());
                boolean wasActive = conveyor.isActive();
                ActiveAlarm existing = conveyor.getActiveAlarms().stream()
                        .filter(alarm -> alarm.getAlarmId().equals(e.getAlarmId()))
                        .findFirst()
                        .orElse(null);
                if (existing != null) {
                    if (existing.getSeverity() != e.getSeverity()
                            || existing.isStopsConveyor() != e.isStopsConveyor()
                            || !Objects.equals(existing.getTypology(), e.getTypology())) {
                        throw new IllegalStateException(
                                "Alarm id is already active with different metadata: " + e.getAlarmId());
                    }
                    yield Map.of("status", "IGNORED_DUPLICATE",
                            "effectiveActivityChanged", false);
                }
                conveyor.getActiveAlarms().add(new ActiveAlarm(
                        e.getAlarmId(), e.getConveyorId(), e.getSeverity(), e.getTypology(),
                        e.isStopsConveyor(), e.getTimestamp()));
                boolean willBeActive = conveyor.isOperatorEnabled() && !support.hasStoppingAlarm(conveyor);
                if (manageLogic && wasActive && !willBeActive) {
                    support.checkpointBeforeConveyorStops(conveyor, e.getTimestamp(), shouldBroadcast);
                }
                conveyor.setActive(willBeActive);
                conveyorService.updateConveyor(conveyor);
                support.updateMovementAfterActivityChange(conveyor, wasActive, e.getTimestamp(), shouldBroadcast);
                if (shouldBroadcast) {
                    broadcastConveyorAlarmState(conveyor, e.getTimestamp());
                }
                yield Map.of("status", "PROCESSED_SUCCESSFULLY",
                        "effectiveActivityChanged", wasActive != conveyor.isActive());
            }

            case AlarmClearedEvent e -> {
                var conveyor = conveyorService.getConveyorById(e.getConveyorId());
                boolean wasActive = conveyor.isActive();
                boolean removed = conveyor.getActiveAlarms()
                        .removeIf(alarm -> alarm.getAlarmId().equals(e.getAlarmId()));
                if (!removed) {
                    yield Map.of("status", "IGNORED_DUPLICATE",
                            "effectiveActivityChanged", false);
                }
                conveyor.setActive(conveyor.isOperatorEnabled() && !support.hasStoppingAlarm(conveyor));
                conveyorService.updateConveyor(conveyor);
                support.updateMovementAfterActivityChange(conveyor, wasActive, e.getTimestamp(), shouldBroadcast);
                if (shouldBroadcast) {
                    broadcastConveyorAlarmState(conveyor, e.getTimestamp());
                }
                yield Map.of("status", "PROCESSED_SUCCESSFULLY",
                        "effectiveActivityChanged", wasActive != conveyor.isActive());
            }

            case ComponentAlarmRaisedEvent e -> reduceComponentAlarmRaised(e, shouldBroadcast);

            case ComponentAlarmClearedEvent e -> reduceComponentAlarmCleared(e, shouldBroadcast);

            case AnomalyEvaluationTickEvent e -> {
                AnomalyProcessingMode analyticsMode = AnomalyProcessingContext.getMode();
                if (analyticsMode == null) {
                    String simulationId = DatabaseContextHolder.getSimulationId();
                    analyticsMode = simulationId == null
                            ? AnomalyProcessingMode.LIVE
                            : e.getTimestamp().isAfter(timeService.physicalNow())
                                    ? AnomalyProcessingMode.FUTURE_SIMULATION
                                    : AnomalyProcessingMode.HISTORICAL_PLAYBACK;
                }
                anomalyEngine.evaluate(e, analyticsMode);
                simulationService.scheduleNextAnomalyTick(e);
                yield Map.of("status", "PROCESSED_SUCCESSFULLY");
            }
            default -> throw new IllegalArgumentException("Unsupported event: " + event.getEventType());
        };
    }

    Map<String, Object> reduceComponentAlarmRaised(ComponentAlarmRaisedEvent event,
            boolean shouldBroadcast) {
        if (event.getComponentType() == flunav.types.ComponentType.CONVEYOR) {
            Conveyor conveyor = conveyorService.getConveyorById(event.getComponentId());
            ActiveAlarm existing = conveyor.getActiveAlarms().stream()
                    .filter(alarm -> alarm.getAlarmId().equals(event.getAlarmId())).findFirst().orElse(null);
            if (existing != null) {
                return Map.of("status", "IGNORED_DUPLICATE", "effectiveActivityChanged", false);
            }
            boolean wasActive = conveyor.isActive();
            conveyor.getActiveAlarms().add(new ActiveAlarm(event.getAlarmId(), event.getComponentId(),
                    event.getFindingId(), event.getComponentType(), event.getSeverity(), event.getTypology(),
                    event.getSource(), event.isStopsComponent(), event.getTimestamp()));
            boolean willBeActive = conveyor.isOperatorEnabled() && !support.hasStoppingAlarm(conveyor);
            if (manageLogic && wasActive && !willBeActive) {
                support.checkpointBeforeConveyorStops(conveyor, event.getTimestamp(), shouldBroadcast);
            }
            conveyor.setActive(willBeActive);
            conveyorService.updateConveyor(conveyor);
            support.updateMovementAfterActivityChange(conveyor, wasActive, event.getTimestamp(), shouldBroadcast);
            if (shouldBroadcast) {
                broadcastConveyorAlarmState(conveyor, event.getTimestamp());
            }
            return Map.of("status", "PROCESSED_SUCCESSFULLY",
                    "effectiveActivityChanged", wasActive != conveyor.isActive());
        }

        Location location = locationService.getLocationById(event.getComponentId());
        if (location.getActiveAlarms().stream().anyMatch(alarm -> alarm.getAlarmId().equals(event.getAlarmId()))) {
            return Map.of("status", "IGNORED_DUPLICATE", "effectiveActivityChanged", false);
        }
        location.getActiveAlarms().add(new ActiveAlarm(event.getAlarmId(), event.getComponentId(),
                event.getFindingId(), event.getComponentType(), event.getSeverity(), event.getTypology(),
                event.getSource(), event.isStopsComponent(), event.getTimestamp()));
        locationService.fullUpdateLocation(location);
        if (shouldBroadcast) {
            webSocketService.broadcastLocationPropertiesUpdated(
                    new UpdateModel(location.getId(), Map.of("activeAlarms", location.getActiveAlarms())),
                    event.getTimestamp());
        }
        return Map.of("status", "PROCESSED_SUCCESSFULLY", "effectiveActivityChanged", false);
    }

    Map<String, Object> reduceComponentAlarmCleared(ComponentAlarmClearedEvent event,
            boolean shouldBroadcast) {
        if (event.getComponentType() == flunav.types.ComponentType.CONVEYOR) {
            Conveyor conveyor = conveyorService.getConveyorById(event.getComponentId());
            boolean wasActive = conveyor.isActive();
            boolean removed = conveyor.getActiveAlarms().removeIf(alarm -> alarm.getAlarmId().equals(event.getAlarmId()));
            if (!removed) {
                return Map.of("status", "IGNORED_DUPLICATE", "effectiveActivityChanged", false);
            }
            conveyor.setActive(conveyor.isOperatorEnabled() && !support.hasStoppingAlarm(conveyor));
            conveyorService.updateConveyor(conveyor);
            support.updateMovementAfterActivityChange(conveyor, wasActive, event.getTimestamp(), shouldBroadcast);
            if (shouldBroadcast) {
                broadcastConveyorAlarmState(conveyor, event.getTimestamp());
            }
            return Map.of("status", "PROCESSED_SUCCESSFULLY",
                    "effectiveActivityChanged", wasActive != conveyor.isActive());
        }

        Location location = locationService.getLocationById(event.getComponentId());
        boolean removed = location.getActiveAlarms().removeIf(alarm -> alarm.getAlarmId().equals(event.getAlarmId()));
        if (!removed) {
            return Map.of("status", "IGNORED_DUPLICATE", "effectiveActivityChanged", false);
        }
        locationService.fullUpdateLocation(location);
        if (shouldBroadcast) {
            webSocketService.broadcastLocationPropertiesUpdated(
                    new UpdateModel(location.getId(), Map.of("activeAlarms", location.getActiveAlarms())),
                    event.getTimestamp());
        }
        return Map.of("status", "PROCESSED_SUCCESSFULLY", "effectiveActivityChanged", false);
    }

    void broadcastConveyorAlarmState(Conveyor conveyor, Instant timestamp) {
        Map<String, Object> updates = new HashMap<>();
        updates.put("active", conveyor.isActive());
        updates.put("operatorEnabled", conveyor.isOperatorEnabled());
        updates.put("activeAlarms", conveyor.getActiveAlarms());
        webSocketService.broadcastConnectionUpdated(new UpdateModel(conveyor.getId(), updates), timestamp);
    }

}
