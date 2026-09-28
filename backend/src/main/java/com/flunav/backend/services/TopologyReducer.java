package com.flunav.backend.services;

import com.flunav.backend.domain.Conveyor;
import com.flunav.backend.models.RedisLiveItem;
import com.flunav.backend.models.UpdateModel;
import com.flunav.backend.models.input.LocationInput;
import com.flunav.backend.models.response.ConveyorResponse;
import com.flunav.backend.models.response.DisplayRuleVisualStyle;
import com.flunav.backend.repositories.LiveItemRepository;
import com.flunav.backend.repositories.LiveConveyorRepository;
import com.flunav.backend.repositories.LiveLocationRepository;
import flunav.events.*;
import flunav.types.LocationType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

/** Domain reduction helpers; invoked only inside EventProcessor context and retry boundaries. */
@Service
final class TopologyReducer {
    private static final Logger logger = LoggerFactory.getLogger(TopologyReducer.class);
    private final ConveyorService conveyorService;
    private final WebSocketService webSocketService;
    private final LiveItemRepository liveItemRepository;
    private final LiveConveyorRepository liveConveyorRepository;
    private final LiveLocationRepository liveLocationRepository;
    private final DisplayRulesService displayRulesService;
    private final TopologyProvider topologyProvider;
    private final boolean manageLogic;
    private final LocationService locationService;
    private final ItemMovementProcessor itemMovementProcessor;
    private final OperationalAnalyticsService operationalAnalyticsService;
    private final EventReductionSupport support;

    TopologyReducer(
            ConveyorService conveyorService,
            WebSocketService webSocketService,
            LiveItemRepository liveItemRepository,
            LiveConveyorRepository liveConveyorRepository,
            LiveLocationRepository liveLocationRepository,
            DisplayRulesService displayRulesService,
            TopologyProvider topologyProvider,
            @Value("${simulation.manage-logic:true}") boolean manageLogic,
            LocationService locationService,
            ItemMovementProcessor itemMovementProcessor,
            OperationalAnalyticsService operationalAnalyticsService,
            EventReductionSupport support) {
        this.conveyorService = conveyorService;
        this.webSocketService = webSocketService;
        this.liveItemRepository = liveItemRepository;
        this.liveConveyorRepository = liveConveyorRepository;
        this.liveLocationRepository = liveLocationRepository;
        this.displayRulesService = displayRulesService;
        this.topologyProvider = topologyProvider;
        this.manageLogic = manageLogic;
        this.locationService = locationService;
        this.itemMovementProcessor = itemMovementProcessor;
        this.operationalAnalyticsService = operationalAnalyticsService;
        this.support = support;
    }

    /** Applies derived state in the caller's context; history persistence stays in EventProcessor. */
    Map<String, Object> reduce(DomainEvent event, boolean shouldBroadcast) {
        return switch (event) {
            case LocationCreatedEvent e -> {
                var location = new LocationInput(e);
                locationService.createLocation(location);
                if (shouldBroadcast) {
                    webSocketService.broadcastLocationCreated(location, e.getTimestamp());
                }
                yield Map.of("status", "PROCESSED_SUCCESSFULLY");
            }

            case LocationPropertiesUpdatedEvent e -> {
                var location = locationService.getLocationById(e.getEntityId());
                location.updateProperties(e);
                Map<String, Object> map = new HashMap<>();
                map.put("properties", location.getProperties());
                var updateModel = new UpdateModel(location.getId(), map);
                locationService.updateLocation(updateModel);
                if (shouldBroadcast) {
                    DisplayRuleVisualStyle style = this.displayRulesService.applyDisplayRules(
                            RuleFieldProjection.locationRootFields(location),
                            location.getProperties(), this.displayRulesService.getDisplayRules());
                    map.put("customColor", style != null ? style.getFillColor() : null);
                    webSocketService.broadcastLocationPropertiesUpdated(updateModel, e.getTimestamp());
                }
                yield Map.of("status", "PROCESSED_SUCCESSFULLY");
            }

            case LocationCoordinatesChangedEvent e -> {
                var location = locationService.getLocationById(e.getEntityId());
                location.updateCoordinates(e.getLatitude(), e.getLongitude());
                var updateModel = new UpdateModel(location.getId(),
                        Map.of("latitude", location.getLatitude(), "longitude", location.getLongitude()));
                locationService.updateLocation(updateModel);
                if (shouldBroadcast) {
                    webSocketService.broadcastLocationPropertiesUpdated(updateModel, e.getTimestamp());
                }
                yield Map.of("status", "PROCESSED_SUCCESSFULLY");
            }

            case LocationCapacityChangedEvent e -> {
                var location = locationService.getLocationById(e.getEntityId());
                Integer oldCapacity = location.getCapacity();
                location.updateCapacity(e.getCapacity());
                var updateModel = new UpdateModel(location.getId(), Map.of("capacity", location.getCapacity()));
                locationService.updateLocation(updateModel);
                if (shouldBroadcast) {
                    webSocketService.broadcastLocationPropertiesUpdated(updateModel, e.getTimestamp());
                }
                if (location.getType() == LocationType.CHUTE
                        && e.getCapacity() != null
                        && (oldCapacity == null || e.getCapacity() > oldCapacity)) {
                    support.retryWaitingHighPriorityItems(e.getTimestamp(), shouldBroadcast);
                }
                yield Map.of("status", "PROCESSED_SUCCESSFULLY");
            }

            case LocationProcessingTimeChangedEvent e -> {
                var location = locationService.getLocationById(e.getEntityId());
                Long timeToProcessMs = e.getTimeToProcessMs() != null && e.getTimeToProcessMs() > 0L
                        ? e.getTimeToProcessMs()
                        : 0L;
                location.updateTimeToProcessMs(timeToProcessMs);
                Map<String, Object> updates = new HashMap<>();
                updates.put("timeToProcessMs", timeToProcessMs);
                var updateModel = new UpdateModel(location.getId(), updates);
                locationService.updateLocation(updateModel);
                if (shouldBroadcast) {
                    webSocketService.broadcastLocationPropertiesUpdated(updateModel, e.getTimestamp());
                }
                yield Map.of("status", "PROCESSED_SUCCESSFULLY");
            }

            case LocationTypeChangedEvent e -> {
                var location = locationService.getLocationById(e.getEntityId());
                location.setType(e.getLocationType());
                var updateModel = new UpdateModel(location.getId(), Map.of("type", location.getType().getValue()));
                locationService.updateLocation(updateModel);
                if (shouldBroadcast) {
                    webSocketService.broadcastLocationPropertiesUpdated(updateModel, e.getTimestamp());
                }
                yield Map.of("status", "PROCESSED_SUCCESSFULLY");
            }

            case LocationDeletedEvent e -> {
                Set<String> removedPositionIds = new HashSet<>();
                removedPositionIds.add(e.getEntityId());
                topologyProvider.getAllConveyors().stream()
                        .filter(conveyor -> e.getEntityId().equals(conveyor.getSourceLocationId())
                                || e.getEntityId().equals(conveyor.getTargetLocationId()))
                        .map(Conveyor::getId)
                        .forEach(removedPositionIds::add);
                removeHotItemsAtPositions(removedPositionIds, e.getTimestamp(), shouldBroadcast);
                removedPositionIds.stream()
                        .filter(positionId -> !e.getEntityId().equals(positionId))
                        .forEach(liveConveyorRepository::deleteConveyor);
                liveLocationRepository.deleteLocation(e.getEntityId());
                locationService.deleteLocation(e.getEntityId());
                if (shouldBroadcast) {
                    webSocketService.broadcastLocationDeleted(e.getEntityId(), e.getTimestamp());
                }
                yield Map.of("status", "PROCESSED_SUCCESSFULLY");
            }

            // --- CONNECTION EVENTS (Conveyors/Edges) ---

            case ConnectionCreatedEvent e -> {
                conveyorService.createConveyor(
                        e.getConnectionId(),
                        e.getSourceId(),
                        e.getTargetId(),
                        e.getName(),
                        e.getLength(),
                        e.getSpeed(),
                        e.getMinDistance(),
                        e.getMainPath(),
                        e.getIsActive(),
                        e.getType(),
                        e.getCapacity(),
                        e.getProperties());
                operationalAnalyticsService.recordSimulationConnectionSignal(
                        e, e.getConnectionId(), e.getIsActive(), e.getSpeed(), e.getSourceId(), e.getTargetId());
                if (shouldBroadcast) {
                    DisplayRuleVisualStyle style = this.displayRulesService.applyDisplayRules(
                            RuleFieldProjection.connectionRootFields(e),
                            e.getProperties(), this.displayRulesService.getDisplayRules());
                    String customColor = style != null ? style.getFillColor() : null;
                    webSocketService.broadcastConnectionCreated(new ConveyorResponse(e.getConnectionId(),
                            e.getSourceId(), e.getTargetId(), e.getName(), e.getLength(), e.getSpeed(),
                            e.getMinDistance(),
                            e.getType(),
                            e.getIsActive(), e.getMainPath(), e.getCapacity(),
                            e.getProperties(), customColor, e.getIsActive(), List.of()), e.getTimestamp());
                }
                yield Map.of("status", "PROCESSED_SUCCESSFULLY");
            }

            case ConnectionSpeedChangedEvent e -> {
                var conveyor = topologyProvider.getConveyorById(e.getEntityId());
                if (conveyor != null) {
                    support.checkpointItems(e.getEntityId(), conveyor.getSpeed(), e.getTimestamp());
                    double oldSpeed = conveyor.getSpeed();
                    conveyor.setSpeed(e.getSpeed());
                    conveyorService.updateConveyor(conveyor);
                    operationalAnalyticsService.recordSimulationConnectionSignal(
                            e, conveyor.getId(), null, e.getSpeed(),
                            conveyor.getSourceLocationId(), conveyor.getTargetLocationId());
                    if (manageLogic) {
                        itemMovementProcessor.recalculateConveyorAccumulation(e.getEntityId());
                        if (oldSpeed <= 0 && e.getSpeed() > 0) {
                            itemMovementProcessor.wakeUpPrecedingConveyors(conveyor.getSourceLocationId());
                        }
                    }
                    if (shouldBroadcast)
                        webSocketService.broadcastConnectionUpdated(
                                new UpdateModel(conveyor.getId(), Map.of("speed", e.getSpeed())), e.getTimestamp());
                }
                yield Map.of("status", "PROCESSED_SUCCESSFULLY");
            }

            case ConnectionLengthChangedEvent e -> {
                var conveyor = conveyorService.getConveyorById(e.getEntityId());
                support.checkpointItems(e.getEntityId(), conveyor.getSpeed(), e.getTimestamp());
                itemMovementProcessor.rescaleProgressForLengthChange(
                        e.getEntityId(), conveyor.getLength(), e.getLength(), e.getTimestamp());
                conveyor.setLength(e.getLength());
                conveyorService.updateConveyor(conveyor);
                if (manageLogic) {
                    itemMovementProcessor.recalculateConveyorAccumulation(e.getEntityId());
                }

                if (shouldBroadcast) {
                    webSocketService.broadcastConnectionUpdated(
                            new UpdateModel(conveyor.getId(), Map.of("length", e.getLength())), e.getTimestamp());
                }
                yield Map.of("status", "PROCESSED_SUCCESSFULLY");
            }

            case ConnectionPropertiesUpdatedEvent e -> {
                var conveyor = conveyorService.getConveyorById(e.getEntityId());

                conveyor.setProperties(e.getUpdatedProperties());
                conveyorService.updateConveyor(conveyor);

                if (shouldBroadcast) {
                    DisplayRuleVisualStyle style = this.displayRulesService.applyDisplayRules(
                            RuleFieldProjection.conveyorRootFields(conveyor),
                            conveyor.getProperties(), this.displayRulesService.getDisplayRules());

                    Map<String, Object> updates = new HashMap<>();
                    updates.put("properties", e.getUpdatedProperties());
                    updates.put("customColor", style != null ? style.getFillColor() : null);

                    webSocketService.broadcastConnectionUpdated(
                            new UpdateModel(conveyor.getId(), updates),
                            e.getTimestamp());
                }

                yield Map.of("status", "PROCESSED_SUCCESSFULLY");
            }

            case ConnectionActivatedEvent e -> {
                var conveyor = conveyorService.getConveyorById(e.getEntityId());
                boolean wasActive = conveyor.isActive();
                conveyor.setOperatorEnabled(true);
                conveyor.setActive(!support.hasStoppingAlarm(conveyor));
                conveyorService.updateConveyor(conveyor);
                support.updateMovementAfterActivityChange(conveyor, wasActive, e.getTimestamp(), shouldBroadcast);
                operationalAnalyticsService.recordSimulationConnectionSignal(
                        e, conveyor.getId(), conveyor.isActive(), null,
                        conveyor.getSourceLocationId(), conveyor.getTargetLocationId());
                if (shouldBroadcast) {
                    Map<String, Object> updates = new HashMap<>();
                    updates.put("operatorEnabled", true);
                    updates.put("active", conveyor.isActive());
                    updates.put("activeAlarms", conveyor.getActiveAlarms());
                    webSocketService.broadcastConnectionUpdated(
                            new UpdateModel(conveyor.getId(), updates), e.getTimestamp());
                }
                yield Map.of("status", "PROCESSED_SUCCESSFULLY",
                        "effectiveActivityChanged", wasActive != conveyor.isActive());
            }

            case ConnectionDeactivatedEvent e -> {
                var conveyor = conveyorService.getConveyorById(e.getEntityId());
                boolean wasActive = conveyor.isActive();
                if (manageLogic && wasActive) {
                    support.checkpointBeforeConveyorStops(conveyor, e.getTimestamp(), shouldBroadcast);
                }
                conveyor.setOperatorEnabled(false);
                conveyor.setActive(false);
                conveyorService.updateConveyor(conveyor);
                support.updateMovementAfterActivityChange(conveyor, wasActive, e.getTimestamp(), shouldBroadcast);
                operationalAnalyticsService.recordSimulationConnectionSignal(
                        e, conveyor.getId(), false, null,
                        conveyor.getSourceLocationId(), conveyor.getTargetLocationId());
                if (shouldBroadcast) {
                    Map<String, Object> updates = new HashMap<>();
                    updates.put("operatorEnabled", false);
                    updates.put("active", false);
                    updates.put("activeAlarms", conveyor.getActiveAlarms());
                    webSocketService.broadcastConnectionUpdated(
                            new UpdateModel(conveyor.getId(), updates), e.getTimestamp());
                }
                yield Map.of("status", "PROCESSED_SUCCESSFULLY",
                        "effectiveActivityChanged", wasActive);
            }

            case ConnectionTypeChangedEvent e -> {
                Conveyor conveyor = conveyorService.getConveyorById(e.getEntityId());
                flunav.types.ConveyorType oldType = conveyor.getType();
                if (oldType == e.getConveyorType()) {
                    yield Map.of("status", "IGNORED_DUPLICATE");
                }
                if (oldType == flunav.types.ConveyorType.STAGING) {
                    itemMovementProcessor.checkpointStagingItems(conveyor, e.getTimestamp(), true,
                            shouldBroadcast);
                } else {
                    support.checkpointItems(conveyor.getId(), conveyor.getSpeed(), e.getTimestamp());
                }
                conveyor.setType(e.getConveyorType());
                if (e.getConveyorType() == flunav.types.ConveyorType.STAGING
                        && conveyor.getMinDistance() == null) {
                    conveyor.setMinDistance(0.1);
                }
                conveyorService.updateConveyor(conveyor);
                if (e.getConveyorType() == flunav.types.ConveyorType.STAGING) {
                    itemMovementProcessor.checkpointStagingItems(conveyor, e.getTimestamp(), true,
                            shouldBroadcast);
                } else if (manageLogic) {
                    itemMovementProcessor.recalculateConveyorAccumulation(conveyor.getId());
                }
                if (shouldBroadcast) {
                    Map<String, Object> updates = new HashMap<>();
                    updates.put("conveyorType", conveyor.getType());
                    updates.put("minDistance", conveyor.getMinDistance());
                    webSocketService.broadcastConnectionUpdated(
                            new UpdateModel(conveyor.getId(), updates), e.getTimestamp());
                }
                yield Map.of("status", "PROCESSED_SUCCESSFULLY");
            }

            case LocationAddToMainPath e -> {
                var conveyor = conveyorService.getConveyorById(e.getEntityId());
                conveyor.setMainPath(true);
                conveyorService.updateConveyor(conveyor);

                if (shouldBroadcast) {
                    webSocketService.broadcastConnectionUpdated(
                            new UpdateModel(conveyor.getId(), Map.of("isMainPath", true)), e.getTimestamp());
                }
                yield Map.of("status", "PROCESSED_SUCCESSFULLY");
            }

            case ConnectionRemoveFromMainPath e -> {
                var conveyor = conveyorService.getConveyorById(e.getEntityId());
                conveyor.setMainPath(false);
                conveyorService.updateConveyor(conveyor);

                if (shouldBroadcast) {
                    webSocketService.broadcastConnectionUpdated(
                            new UpdateModel(conveyor.getId(), Map.of("isMainPath", false)), e.getTimestamp());
                }
                yield Map.of("status", "PROCESSED_SUCCESSFULLY");
            }

            case ConnectionDeletedEvent e -> {
                Set<String> removedConveyorIds = topologyProvider.getAllConveyors().stream()
                        .filter(conveyor -> e.getSourceLocationId().equals(conveyor.getSourceLocationId())
                                && e.getTargetLocationId().equals(conveyor.getTargetLocationId()))
                        .map(Conveyor::getId)
                        .collect(java.util.stream.Collectors.toSet());
                String removedConveyorId = removedConveyorIds.stream().sorted().findFirst().orElse(null);
                removeHotItemsAtPositions(removedConveyorIds, e.getTimestamp(), shouldBroadcast);
                conveyorService.deleteConveyor(e.getSourceLocationId(), e.getTargetLocationId());
                operationalAnalyticsService.recordSimulationConnectionSignal(
                        e, removedConveyorId, null, null,
                        e.getSourceLocationId(), e.getTargetLocationId());
                if (shouldBroadcast) {
                    webSocketService.broadcastConnectionDeleted(e.getSourceLocationId(), e.getTargetLocationId(),
                            e.getTimestamp());
                }
                yield Map.of("status", "PROCESSED_SUCCESSFULLY");
            }
            default -> throw new IllegalArgumentException("Unsupported event: " + event.getEventType());
        };
    }

    /**
     * Removes hot positions and movement schedules made invalid by topology
     * deletion. The surrounding topology event remains the replayable cause, while
     * durable item metadata is retained for history and later repositioning.
     */
    void removeHotItemsAtPositions(Set<String> positionIds, Instant timestamp, boolean shouldBroadcast) {
        if (positionIds == null || positionIds.isEmpty()) {
            return;
        }
        for (RedisLiveItem item : liveItemRepository.getAllActiveItems()) {
            if (item == null || !positionIds.contains(item.getPositionId())) {
                continue;
            }
            itemMovementProcessor.cancelScheduledEvent(item.getId());
            liveItemRepository.deleteItem(item.getId());
            if (shouldBroadcast) {
                webSocketService.broadcastPositionLost(item.getId(), timestamp);
            }
        }
    }

}
