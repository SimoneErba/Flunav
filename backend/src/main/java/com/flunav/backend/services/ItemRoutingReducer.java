package com.flunav.backend.services;

import com.flunav.backend.domain.Conveyor;
import com.flunav.backend.exception.DuplicateItemException;
import com.flunav.backend.models.RedisLiveItem;
import com.flunav.backend.models.UpdateModel;
import com.flunav.backend.models.input.ItemInput;
import com.flunav.backend.models.response.DisplayRuleVisualStyle;
import com.flunav.backend.models.response.ItemResponse;
import com.flunav.backend.repositories.LiveItemRepository;
import com.flunav.backend.repositories.LiveConveyorRepository;
import com.flunav.backend.repositories.LiveLocationRepository;
import flunav.events.*;
import flunav.types.LocationType;
import flunav.types.PositionType;
import flunav.types.RoutingStatus;

import org.modelmapper.ModelMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AmqpTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.function.BiFunction;
import com.flunav.backend.context.DatabaseContextHolder;
import com.flunav.backend.utils.SimulationRunTiming;

/** Domain reduction helpers; invoked only inside EventProcessor context and retry boundaries. */
@Service
final class ItemRoutingReducer {
    private static final Logger logger = LoggerFactory.getLogger(ItemRoutingReducer.class);
    private final ClickHouseService clickHouseService;
    private final ItemService itemService;
    private final ConveyorService conveyorService;
    private final WebSocketService webSocketService;
    private final LiveItemRepository liveItemRepository;
    private final LiveConveyorRepository liveConveyorRepository;
    private final LiveLocationRepository liveLocationRepository;
    private final DisplayRulesService displayRulesService;
    private final AmqpTemplate amqpTemplate;
    private final String commandsQueue;
    private final TopologyProvider topologyProvider;
    private final boolean manageLogic;
    private final ItemMovementProcessor itemMovementProcessor;
    private final DestinationMappingService destinationMappingService;
    private final SensorMappingService sensorMappingService;
    private final RoutingDecisionService routingDecisionService;
    private final RoutingCoordinator routingCoordinator;
    private final PathAssignmentPublisher pathAssignmentPublisher;
    private final OperationalAnalyticsService operationalAnalyticsService;
    private final AnomalyObservationService anomalyObservationService;
    private final MultiSimulationMetricsService multiSimulationMetricsService;
    private final EventReductionSupport support;
    private final ModelMapper modelMapper = new ModelMapper();

    ItemRoutingReducer(
            ClickHouseService clickHouseService,
            ItemService itemService,
            ConveyorService conveyorService,
            WebSocketService webSocketService,
            LiveItemRepository liveItemRepository,
            LiveConveyorRepository liveConveyorRepository,
            LiveLocationRepository liveLocationRepository,
            DisplayRulesService displayRulesService,
            AmqpTemplate amqpTemplate,
            @Value("${rabbitmq.queue.commands}") String commandsQueue,
            TopologyProvider topologyProvider,
            @Value("${simulation.manage-logic:true}") boolean manageLogic,
            ItemMovementProcessor itemMovementProcessor,
            DestinationMappingService destinationMappingService,
            SensorMappingService sensorMappingService,
            RoutingDecisionService routingDecisionService,
            RoutingCoordinator routingCoordinator,
            PathAssignmentPublisher pathAssignmentPublisher,
            OperationalAnalyticsService operationalAnalyticsService,
            AnomalyObservationService anomalyObservationService,
            MultiSimulationMetricsService multiSimulationMetricsService,
            EventReductionSupport support) {
        this.clickHouseService = clickHouseService;
        this.itemService = itemService;
        this.conveyorService = conveyorService;
        this.webSocketService = webSocketService;
        this.liveItemRepository = liveItemRepository;
        this.liveConveyorRepository = liveConveyorRepository;
        this.liveLocationRepository = liveLocationRepository;
        this.displayRulesService = displayRulesService;
        this.amqpTemplate = amqpTemplate;
        this.commandsQueue = commandsQueue;
        this.topologyProvider = topologyProvider;
        this.manageLogic = manageLogic;
        this.itemMovementProcessor = itemMovementProcessor;
        this.destinationMappingService = destinationMappingService;
        this.sensorMappingService = sensorMappingService;
        this.routingDecisionService = routingDecisionService;
        this.routingCoordinator = routingCoordinator;
        this.pathAssignmentPublisher = pathAssignmentPublisher;
        this.operationalAnalyticsService = operationalAnalyticsService;
        this.anomalyObservationService = anomalyObservationService;
        this.multiSimulationMetricsService = multiSimulationMetricsService;
        this.support = support;
    }

    /** Applies derived state in the caller's context; history persistence stays in EventProcessor. */
    Map<String, Object> reduce(DomainEvent event, boolean shouldBroadcast,
            BiFunction<DomainEvent, Boolean, Map<String, Object>> reduction) {
        return switch (event) {
            case ItemCreatedEvent e -> {
                try {
                    long creationStep = SimulationRunTiming.tick();
                    validatePriority(e.getPriority());
                    validateItemProperties(e.getProperties());
                    var item = new ItemInput(e);
                    SimulationRunTiming.record("create.validate-input", creationStep);
                    creationStep = SimulationRunTiming.tick();
                    AppliedDestination appliedDestination = routingCoordinator.withRoutingLock(() -> {
                        long routingStarted = SimulationRunTiming.tick();
                        AppliedDestination destination = applyDestinationToCreatedItem(item, e.getTimestamp());
                        SimulationRunTiming.record("create.assign-destination", routingStarted);
                        itemService.createItem(item);
                        return destination;
                    });
                    SimulationRunTiming.record("create.route-and-save", creationStep);
                    creationStep = SimulationRunTiming.tick();
                    publishDestinationCommandIfNeeded(e, item, appliedDestination, shouldBroadcast);
                    pathAssignmentPublisher.publishIfAssigned(
                            item.getId(),
                            appliedDestination.selectedExitId(),
                            appliedDestination.routingStatus(),
                            appliedDestination.path(),
                            e.getTimestamp(),
                            shouldBroadcast);
                    SimulationRunTiming.record("create.publish-assignment", creationStep);


                    if (shouldBroadcast) {
                        ItemResponse response = modelMapper.map(item, ItemResponse.class);
                        applyCurrentItemCheckpoint(response);
                        DestinationMappingService.RushPriority rush = destinationMappingService.evaluateRush(
                                RuleFieldProjection.itemRootFields(item), item.getProperties(), item.getDestinations(),
                                item.getPriority(), e.getTimestamp());
                        response.setEffectivePriority(rush.effectivePriority());
                        response.setRushActive(rush.rushActive());
                        DisplayRuleVisualStyle style = this.displayRulesService.applyDisplayRules(
                                RuleFieldProjection.itemRootFields(item), item.getProperties(), this.displayRulesService.getDisplayRules());
                        if (style != null) {
                            response.setCustomColor(style.getFillColor());
                            response.setCustomBorderColor(style.getBorderColor());
                            response.setCustomBorderWidth(style.getBorderWidth());
                        }
                        webSocketService.broadcastItemCreated(response, e.getTimestamp());
                    }
                    creationStep = SimulationRunTiming.tick();
                    if (item.getLocationId() != null) {
                        var positionType = topologyProvider.getPositionType(item.getLocationId());
                        if (positionType == PositionType.CONVEYOR) {
                            liveConveyorRepository.addItemToConveyor(item.getLocationId(), e.getEntityId(),
                                    e.getTimestamp());
                            itemMovementProcessor.handleItemEntryToConveyor(e.getEntityId(), item.getLocationId(),
                                    e.getTimestamp(), e.getProgress(), null, shouldBroadcast);
                        } else {
                            itemMovementProcessor.processLocationEntry(e.getEntityId(), item.getLocationId(),
                                    e.getTimestamp(), shouldBroadcast);
                        }
                        if (shouldBroadcast && !Objects.equals(item.getLocationId(),
                                liveItemRepository.getItemState(e.getEntityId()).getPositionId())) {
                            broadcastCurrentItemCheckpoint(e.getEntityId());
                        }
                    }
                    SimulationRunTiming.record("create.enter-network", creationStep);
                    yield Map.of("status", "CREATED", "itemId", e.getEntityId());
                } catch (DuplicateItemException die) {
                    logger.warn("Received a duplicate ItemCreatedEvent for existing item '{}'. Ignoring event.",
                            e.getEntityId());
                    yield Map.of("status", "IGNORED_DUPLICATE", "itemId", e.getEntityId());
                }
            }

            case ItemPositionChangedEvent e -> {
                itemMovementProcessor.cancelScheduledEvent(e.getEntityId());
                liveItemRepository.setMovementCheck(e.getEntityId(), null);
                liveItemRepository.setFlowPaused(e.getEntityId(), false);
                long stepStarted = SimulationRunTiming.tick();
                ResolvedPosition resolvedPosition = resolvePosition(e);
                String positionId = resolvedPosition.positionId();
                Double progress = resolvedPosition.progress();
                PositionType positionType = resolvedPosition.positionType();
                ItemPositionChangedEvent resolvedEvent = new ItemPositionChangedEvent(
                        e.getEntityId(), positionId, progress, e.getTimestamp());
                SimulationRunTiming.record("position.resolve", stepStarted);

                stepStarted = SimulationRunTiming.tick();
                var lastState = liveItemRepository.getItemState(e.getEntityId());
                String previousPosId = (lastState != null) ? lastState.getPositionId() : null;
                var lastPositionType = (lastState != null) ? lastState.getType() : null;
                SimulationRunTiming.record("position.read-state", stepStarted);

                stepStarted = SimulationRunTiming.tick();
                // --- REMOVE FROM PREVIOUS POSITION ---
                if (previousPosId != null) {
                    if (PositionType.CONVEYOR.equals(lastPositionType)) {
                        liveConveyorRepository.removeItemFromConveyor(previousPosId, e.getEntityId());
                    } else {
                        var previousLocation = topologyProvider.getLocationById(previousPosId);
                        // Only remove locations that own transient occupancy state.
                        if (previousLocation != null
                                && (previousLocation.getType() == LocationType.CHUTE
                                        || previousLocation.getType() == LocationType.TIMED_NODE)) {
                            liveLocationRepository.removeItemFromLocation(previousPosId, e.getEntityId());
                        }
                    }
                }
                SimulationRunTiming.record("position.remove-old-occupancy", stepStarted);

                stepStarted = SimulationRunTiming.tick();
                itemService.updateItemPosition(e.getEntityId(), positionId, positionType, e.getTimestamp(),
                        progress, null);
                SimulationRunTiming.record("position.write-state", stepStarted);

                stepStarted = SimulationRunTiming.tick();
                // --- ADD TO NEW POSITION ---
                if (positionType == PositionType.CONVEYOR) {
                    liveConveyorRepository.addItemToConveyor(positionId, e.getEntityId(), e.getTimestamp());
                    itemMovementProcessor.handleItemEntryToConveyor(e.getEntityId(), positionId,
                            e.getTimestamp(), progress,
                            previousPosId, shouldBroadcast);
                } else {
                    itemMovementProcessor.processLocationEntry(e.getEntityId(), positionId,
                            e.getTimestamp(), shouldBroadcast);
                }
                if (lastPositionType == PositionType.CONVEYOR && previousPosId != null) {
                    itemMovementProcessor.onConveyorOccupancyChanged(previousPosId, e.getTimestamp(), shouldBroadcast);
                }
                SimulationRunTiming.record("position.movement-routing", stepStarted);

                stepStarted = SimulationRunTiming.tick();
                anomalyObservationService.collectPositionChange(resolvedEvent, lastState, positionType);
                SimulationRunTiming.record("position.anomaly", stepStarted);

                if (shouldBroadcast) {
                    broadcastCurrentItemCheckpoint(e.getEntityId());
                }

                yield Map.of("status", "PROCESSED_SUCCESSFULLY");
            }

            case ItemMovementCheckEvent e -> {
                itemMovementProcessor.processMovementCheck(e, shouldBroadcast,
                        position -> reduction.apply(position, shouldBroadcast));
                yield Map.of("status", "PROCESSED_SUCCESSFULLY");
            }

            case ItemProcessingCompletedEvent e -> {
                itemMovementProcessor.processTimedNodeCompletion(e.getEntityId(), e.getLocationId(),
                        e.getTimestamp(), shouldBroadcast);
                yield Map.of("status", "PROCESSED_SUCCESSFULLY");
            }

            case ItemPositionDeletedEvent e -> {
                var lastState = liveItemRepository.getItemState(e.getEntityId());
                // This logic is now handled by liveItemRepository.deleteItem(), which is more
                // robust
                itemMovementProcessor.cancelScheduledEvent(e.getEntityId());
                itemService.deleteItem(e.getEntityId());
                if (lastState != null && lastState.getType() == PositionType.CONVEYOR) {
                    itemMovementProcessor.onConveyorOccupancyChanged(
                            lastState.getPositionId(), e.getTimestamp(), shouldBroadcast);
                }
                if (shouldBroadcast)
                    webSocketService.broadcastPositionLost(e.getEntityId(), e.getTimestamp());
                yield Map.of("status", "PROCESSED_SUCCESSFULLY");
            }

            case ItemRenamedEvent e -> {
                var item = itemService.getItemById(e.getEntityId());
                itemService.updateItem(new UpdateModel(item.getId(), Map.of("name", e.getNewName())));
                item.setName(e.getNewName());
                if (shouldBroadcast) {
                    Map<String, Object> updates = new HashMap<>();
                    updates.put("name", e.getNewName());
                    support.addRushFields(updates, item, e.getTimestamp());
                    webSocketService.broadcastItemUpdated(
                            new UpdateModel(item.getId(), updates), e.getTimestamp());
                }
                yield Map.of("status", "PROCESSED_SUCCESSFULLY");
            }

            case ItemSpeedChangedEvent e -> {
                var item = itemService.getItemById(e.getEntityId());
                itemService.updateItem(new UpdateModel(item.getId(), Map.of("speed", e.getSpeed())));

                if (shouldBroadcast) {
                    webSocketService.broadcastItemUpdated(
                            new UpdateModel(item.getId(), Map.of("speed", e.getSpeed())), e.getTimestamp());
                }
                yield Map.of("status", "PROCESSED_SUCCESSFULLY");
            }

            case ItemDeactivatedEvent e -> {
                var item = itemService.getItemById(e.getEntityId());
                itemMovementProcessor.checkpointItem(e.getEntityId(), e.getTimestamp(), shouldBroadcast);
                liveItemRepository.setMovementPaused(e.getEntityId(), true);
                liveItemRepository.clearPlannedTransition(e.getEntityId());
                itemService.updateItem(new UpdateModel(item.getId(), Map.of("active", false)));
                item.stop();
                itemMovementProcessor.cancelScheduledEvent(e.getEntityId());
                if (shouldBroadcast) {
                    Map<String, Object> updates = new HashMap<>();
                    updates.put("active", false);
                    support.addRushFields(updates, item, e.getTimestamp());
                    webSocketService.broadcastItemUpdated(
                            new UpdateModel(item.getId(), updates), e.getTimestamp());
                }
                yield Map.of("status", "PROCESSED_SUCCESSFULLY");
            }

            case ItemActivatedEvent e -> {
                var item = itemService.getItemById(e.getEntityId());
                itemService.updateItem(new UpdateModel(item.getId(), Map.of("active", true)));
                item.resume();
                itemMovementProcessor.resumeItem(e.getEntityId(), e.getTimestamp(), shouldBroadcast);
                if (shouldBroadcast) {
                    Map<String, Object> updates = new HashMap<>();
                    updates.put("active", true);
                    support.addRushFields(updates, item, e.getTimestamp());
                    webSocketService.broadcastItemUpdated(
                            new UpdateModel(item.getId(), updates), e.getTimestamp());
                }
                yield Map.of("status", "PROCESSED_SUCCESSFULLY");
            }

            case ItemPropertiesUpdatedEvent e -> {
                validateItemProperties(e.getProperties());
                var item = itemService.getItemById(e.getEntityId());
                item.updateProperties(e);
                Map<String, Object> map = new HashMap<>();
                map.put("properties", item.getProperties());
                var updateModel = new UpdateModel(item.getId(), map);
                itemService.updateItem(updateModel);
                if (shouldBroadcast) {
                    DisplayRuleVisualStyle style = this.displayRulesService.applyDisplayRules(
                            RuleFieldProjection.itemRootFields(item), item.getProperties(), this.displayRulesService.getDisplayRules());
                    map.put("customColor", style != null ? style.getFillColor() : null);
                    map.put("customBorderColor", style != null ? style.getBorderColor() : null);
                    map.put("customBorderWidth", style != null ? style.getBorderWidth() : null);
                    support.addRushFields(map, item, e.getTimestamp());
                    webSocketService.broadcastItemUpdated(updateModel, e.getTimestamp());
                }
                yield Map.of("status", "PROCESSED_SUCCESSFULLY");
            }

            case ItemPriorityUpdatedEvent e -> {
                validatePriority(e.getPriority());
                var item = itemService.getItemById(e.getEntityId());
                if (item == null) {
                    throw new IllegalArgumentException("Item does not exist: " + e.getEntityId());
                }
                itemService.updateItem(new UpdateModel(item.getId(), Map.of("priority", e.getPriority())));
                item.setPriority(e.getPriority());
                if (shouldBroadcast) {
                    DisplayRuleVisualStyle style = displayRulesService.applyDisplayRules(
                            RuleFieldProjection.itemRootFields(item), item.getProperties(), displayRulesService.getDisplayRules());
                    Map<String, Object> updates = new HashMap<>();
                    updates.put("priority", e.getPriority());
                    updates.put("customColor", style != null ? style.getFillColor() : null);
                    updates.put("customBorderColor", style != null ? style.getBorderColor() : null);
                    updates.put("customBorderWidth", style != null ? style.getBorderWidth() : null);
                    support.addRushFields(updates, item, e.getTimestamp());
                    webSocketService.broadcastItemUpdated(new UpdateModel(item.getId(), updates), e.getTimestamp());
                }
                yield Map.of("status", "PROCESSED_SUCCESSFULLY");
            }

            case ItemDeletedEvent e -> {
                var lastState = liveItemRepository.getItemState(e.getEntityId());
                if (lastState != null) {
                    if (lastState.getPositionId() != null && lastState.getType() != null) {
                        recordPathTraversal(new PathTraversedEvent(
                                e.getEntityId(),
                                lastState.getPositionId(),
                                lastState.getType(),
                                lastState.getPositionId(),
                                lastState.getType(),
                                List.of(lastState.getPositionId()),
                                e.getTimestamp()));
                    }
                }
                itemMovementProcessor.cancelScheduledEvent(e.getEntityId());
                itemService.deleteItem(e.getEntityId());
                liveItemRepository.deleteItem(e.getEntityId());
                if (lastState != null && lastState.getType() == PositionType.CONVEYOR) {
                    itemMovementProcessor.onConveyorOccupancyChanged(
                            lastState.getPositionId(), e.getTimestamp(), shouldBroadcast);
                }

                if (shouldBroadcast)
                    webSocketService.broadcastItemDeleted(e.getEntityId(), e.getTimestamp());
                yield Map.of("status", "PROCESSED_SUCCESSFULLY");
            }

            case ItemDestinationEvent e -> {
                var item = itemService.getItemById(e.getEntityId());
                if (item == null) {
                    throw new IllegalArgumentException("Item does not exist: " + e.getEntityId());
                }
                List<String> previousPath = item.getPath();
                RoutingDecisionService.RoutingDecision decision = item.getPositionId() == null
                        ? RoutingDecisionService.RoutingDecision.none()
                        : routingCoordinator.withRoutingLock(() -> {
                            RoutingDecisionService.RoutingDecision selected =
                                    routingDecisionService.selectRouteToExit(
                                            item,
                                            item.getPositionId(),
                                            item.getPositionType() != null ? item.getPositionType()
                                                    : PositionType.LOCATION,
                                            e.getLocationId(),
                                            e.getTimestamp());
                            item.setSelectedExitId(selected.selectedExitId());
                            item.setRoutingStatus(selected.routingStatus());
                            item.setRoutingStatusUpdatedAt(e.getTimestamp());
                            item.setPath(selected.path());
                            itemService.updateItemRouting(
                                    item.getId(), item.getDestinations(), selected.selectedExitId(),
                                    selected.routingStatus(), e.getTimestamp(), selected.path());
                            return selected;
                        });
                pathAssignmentPublisher.publishIfAssigned(
                        item.getId(),
                        decision.selectedExitId(),
                        decision.routingStatus(),
                        decision.path(),
                        e.getTimestamp(),
                        shouldBroadcast);

                recordCurrentPathTraversal(item.getId(), decision.path(), e.getTimestamp());
                operationalAnalyticsService.recordRecirculation(
                        item.getId(), previousPath, decision.path(), e.getTimestamp());
                itemMovementProcessor.refreshItemMovement(e.getEntityId(), e.getTimestamp(), shouldBroadcast);

                if (shouldBroadcast) {
                    Map<String, Object> updates = new HashMap<>();
                    updates.put("selectedExitId", decision.selectedExitId());
                    updates.put("routingStatus", decision.routingStatus());
                    updates.put("routingStatusUpdatedAt", e.getTimestamp());
                    updates.put("path", decision.path());
                    support.addRushFields(updates, item, e.getTimestamp());
                    webSocketService.broadcastItemUpdated(new UpdateModel(item.getId(), updates), e.getTimestamp());
                }
                yield Map.of("status", "PROCESSED_SUCCESSFULLY");
            }

            case ItemRoutingDecisionRequestedEvent e -> {
                var item = itemService.getItemById(e.getEntityId());
                if (item == null) {
                    throw new IllegalArgumentException("Item does not exist: " + e.getEntityId());
                }
                if (!manageLogic) {
                    yield Map.of("status", "IGNORED_MANAGED_LOGIC_DISABLED");
                }

                List<String> previousPath = item.getPath();
                RoutingDecisionService.RoutingDecision decision = routingCoordinator.withRoutingLock(() -> {
                    RoutingDecisionService.RoutingDecision selected = routingDecisionService.selectRoute(
                            item, e.getDecisionPointId(), PositionType.LOCATION, e.getTimestamp());
                    itemService.updateItemRouting(
                            item.getId(), item.getDestinations(), selected.selectedExitId(),
                            selected.routingStatus(), e.getTimestamp(), selected.path());
                    return selected;
                });
                pathAssignmentPublisher.publishIfAssigned(
                        item.getId(),
                        decision.selectedExitId(),
                        decision.routingStatus(),
                        decision.path(),
                        e.getTimestamp(),
                        shouldBroadcast);
                operationalAnalyticsService.recordRecirculation(
                        item.getId(), previousPath, decision.path(), e.getTimestamp());

                if (shouldBroadcast) {
                    Map<String, Object> updates = new HashMap<>();
                    updates.put("selectedExitId", decision.selectedExitId());
                    updates.put("routingStatus", decision.routingStatus());
                    updates.put("routingStatusUpdatedAt", e.getTimestamp());
                    updates.put("path", decision.path());
                    support.addRushFields(updates, item, e.getTimestamp());
                    webSocketService.broadcastItemUpdated(new UpdateModel(item.getId(), updates), e.getTimestamp());
                }

                yield Map.of(
                        "status", "PROCESSED_SUCCESSFULLY",
                        "nextConveyorId", Objects.toString(decision.nextConveyorId(), ""),
                        "selectedExitId", Objects.toString(decision.selectedExitId(), ""));
            }

            case ItemPathChangedEvent e -> {
                RedisLiveItem previousState = liveItemRepository.getItemState(e.getEntityId());
                itemService.updateItemPath(e.getEntityId(), e.getPath());
                recordCurrentPathTraversal(e.getEntityId(), e.getPath(), e.getTimestamp());
                operationalAnalyticsService.recordRecirculation(
                        e.getEntityId(), previousState != null ? previousState.getPath() : null,
                        e.getPath(), e.getTimestamp());
                itemMovementProcessor.refreshItemMovement(e.getEntityId(), e.getTimestamp(), shouldBroadcast);
                if (shouldBroadcast) {
                    webSocketService.broadcastItemUpdated(
                            new UpdateModel(e.getEntityId(), Map.of("path", e.getPath())), e.getTimestamp());
                }
                yield Map.of("status", "PROCESSED_SUCCESSFULLY");
            }

            // --- LOCATION EVENTS (Nodes) ---

            case ItemExitedEvent e -> {
                RedisLiveItem lastState = liveItemRepository.getItemState(e.getEntityId());
                if (lastState == null) {
                    yield Map.of("status", "IGNORED_MISSING", "itemsExited", 0L);
                }
                String positionId = lastState.getPositionId() != null
                        ? lastState.getPositionId()
                        : e.getLocationId();
                PositionType positionType = lastState.getType() != null
                        ? lastState.getType()
                        : PositionType.LOCATION;
                operationalAnalyticsService.recordSuccessfulExit(e, positionId, lastState, e.getEntityId());
                if (positionId != null) {
                    recordPathTraversal(new PathTraversedEvent(
                            e.getEntityId(),
                            positionId,
                            positionType,
                            positionId,
                            positionType,
                            List.of(positionId),
                            e.getTimestamp()));
                }
                itemMovementProcessor.cancelScheduledEvent(e.getEntityId());
                liveItemRepository.deleteItem(e.getEntityId());
                if (shouldBroadcast) {
                    webSocketService.broadcastItemDeleted(e.getEntityId(), e.getTimestamp());
                }
                support.retryWaitingHighPriorityItems(e.getTimestamp(), shouldBroadcast);
                if (manageLogic && positionId != null) {
                    itemMovementProcessor.wakeUpPrecedingConveyors(positionId);
                }
                yield Map.of("status", "PROCESSED_SUCCESSFULLY", "itemsExited", 1L);
            }

            case ChuteEmptyEvent e -> {
                Set<String> items = Optional.ofNullable(liveLocationRepository.getItemsAtLocation(e.getEntityId()))
                        .orElseGet(Set::of);
                long removedItems = 0;
                for (String item : items) {
                    RedisLiveItem itemState = liveItemRepository.getItemState(item);
                    operationalAnalyticsService.recordSuccessfulExit(e, e.getEntityId(), itemState, item);
                    String positionId = itemState != null && itemState.getPositionId() != null
                            ? itemState.getPositionId()
                            : e.getEntityId();
                    PositionType positionType = itemState != null && itemState.getType() != null
                            ? itemState.getType()
                            : PositionType.LOCATION;
                    recordPathTraversal(new PathTraversedEvent(
                            item,
                            positionId,
                            positionType,
                            positionId,
                            positionType,
                            List.of(positionId),
                            e.getTimestamp()));
                    liveLocationRepository.removeItemFromLocation(e.getEntityId(), item);
                    liveItemRepository.deleteItem(item);
                    removedItems++;
                }
                if (shouldBroadcast)
                    webSocketService.broadcastChuteEmptied(e.getEntityId(), e.getTimestamp());

                support.retryWaitingHighPriorityItems(e.getTimestamp(), shouldBroadcast);
                if (manageLogic) {
                    itemMovementProcessor.wakeUpPrecedingConveyors(e.getEntityId());
                }
                yield Map.of(
                        "status", "PROCESSED_SUCCESSFULLY",
                        "itemsExited", removedItems);
            }

            case ReleaseStagingConveyorEvent e -> {
                Conveyor conveyor = conveyorService.getConveyorById(e.getEntityId());
                if (conveyor.getType() != flunav.types.ConveyorType.STAGING) {
                    throw new IllegalStateException("Conveyor is not a staging conveyor: " + e.getEntityId());
                }
                if (!conveyor.isActive()) {
                    throw new IllegalStateException("Staging conveyor is inactive: " + e.getEntityId());
                }
                if (conveyor.getSpeed() == null || conveyor.getSpeed() <= 0.0) {
                    throw new IllegalStateException("Staging conveyor speed must be positive: " + e.getEntityId());
                }
                yield itemMovementProcessor.releaseStagingConveyor(conveyor, e.getTimestamp(), shouldBroadcast);
            }

            case PathTraversedEvent e -> {
                recordPathTraversal(e);
                yield Map.of("status", "PROCESSED_SUCCESSFULLY");
            }
            default -> throw new IllegalArgumentException("Unsupported event: " + event.getEventType());
        };
    }

    /**
     * Resolves raw external position identifiers without altering the event that is
     * persisted for replay. Topology IDs retain their existing precedence.
     */
    ResolvedPosition resolvePosition(ItemPositionChangedEvent event) {
        RuntimeException topologyFailure;
        try {
            return new ResolvedPosition(event.getLocationId(), event.getProgress(),
                    topologyProvider.getPositionType(event.getLocationId()));
        } catch (RuntimeException e) {
            topologyFailure = e;
        }

        return sensorMappingService.findBySensorName(event.getLocationId())
                .map(mapping -> new ResolvedPosition(mapping.getConveyorId(), mapping.getProgress(),
                        PositionType.CONVEYOR))
                .orElseThrow(() -> topologyFailure);
    }

    private record ResolvedPosition(String positionId, Double progress, PositionType positionType) {
    }

    /**
     * Records a path-assignment fact from the item's current hot position.
     * The analytics event is reduced in-thread so simulation ThreadLocal context is
     * captured before any async queue flush can occur.
     */
    void recordCurrentPathTraversal(String itemId, List<String> path, Instant timestamp) {
        if (path == null) {
            return;
        }

        RedisLiveItem itemState = liveItemRepository.getItemState(itemId);
        if (itemState == null || itemState.getPositionId() == null) {
            return;
        }

        PositionType positionType = itemState.getType() != null ? itemState.getType() : PositionType.LOCATION;
        recordPathTraversal(new PathTraversedEvent(
                itemId,
                itemState.getPositionId(),
                positionType,
                itemState.getPositionId(),
                positionType,
                path,
                timestamp));
    }

    /**
     * Writes path traversal analytics without adding analytics-only facts to the
     * replay event store or crossing RabbitMQ boundaries.
     */
    void recordPathTraversal(PathTraversedEvent event) {
        if (multiSimulationMetricsService.isCollectingCurrentSimulation()) {
            return;
        }
        clickHouseService.savePathTraversalMetricAsync(event);
    }

    /**
     * Copies persisted position and routing into the websocket DTO. Creation is
     * announced before managed movement; a separate checkpoint announces release.
     */
    void applyCurrentItemCheckpoint(ItemResponse response) {
        var state = liveItemRepository.getItemState(response.getId());
        if (state == null) {
            throw new IllegalStateException("Missing item checkpoint: " + response.getId());
        }
        boolean onConveyor = state.getType() == PositionType.CONVEYOR;
        response.setCurrentEdgeId(onConveyor ? state.getPositionId() : null);
        response.setLocationId(onConveyor ? null : state.getPositionId());
        response.setEntryTimestamp(state.getEntryTime());
        var conveyor = onConveyor ? topologyProvider.getConveyorById(state.getPositionId()) : null;
        response.setProgress(conveyor != null
                ? Math.min(1.0, Math.max(0.0, state.getAccumulatedDistance() / 100.0)) : 0.0);
        response.setDestinations(state.getDestinations());
        response.setSelectedExitId(state.getSelectedExitId());
        response.setRoutingStatus(state.getRoutingStatus());
        response.setRoutingStatusUpdatedAt(state.getRoutingStatusUpdatedAt());
        response.setPath(state.getPath());
        response.setPlannedPositionId(state.getPlannedPositionId());
        response.setPlannedPositionType(state.getPlannedPositionType());
        response.setPlannedTransitionTimestamp(state.getPlannedTransitionTimestamp());
    }

    /** Announces the actual position after immediate junction-release side effects. */
    void broadcastCurrentItemCheckpoint(String itemId) {
        ItemResponse checkpoint = new ItemResponse();
        checkpoint.setId(itemId);
        applyCurrentItemCheckpoint(checkpoint);
        boolean onConveyor = checkpoint.getCurrentEdgeId() != null;
        webSocketService.broadcastPositionUpdate(itemId,
                onConveyor ? checkpoint.getCurrentEdgeId() : checkpoint.getLocationId(),
                checkpoint.getEntryTimestamp(), onConveyor ? PositionType.CONVEYOR : PositionType.LOCATION,
                checkpoint.getProgress() * 100.0);
    }

    /**
     * Resolves destinations and an initial route for a newly created item.
     * Explicit destinations win; otherwise event fields are evaluated against the
     * destination mapping table at the item's domain timestamp.
     */
    AppliedDestination applyDestinationToCreatedItem(ItemInput item, Instant timestamp) {
        List<String> explicitDestinations = normalizeDestinations(item.getDestinations());
        List<String> destinations = !explicitDestinations.isEmpty()
                ? explicitDestinations
                : destinationMappingService.resolveDestinations(RuleFieldProjection.itemRootFields(item), item.getProperties(), timestamp);
        item.setDestinations(destinations);
        if (destinations.isEmpty()) {
            item.setSelectedExitId(null);
            item.setPath(null);
            item.setRoutingStatus(RoutingStatus.UNROUTED);
            item.setRoutingStatusUpdatedAt(timestamp);
            return new AppliedDestination(destinations, null, RoutingStatus.UNROUTED, null);
        }
        if (item.getLocationId() == null) {
            item.setRoutingStatus(RoutingStatus.UNROUTED);
            item.setRoutingStatusUpdatedAt(timestamp);
            return new AppliedDestination(destinations, null, RoutingStatus.UNROUTED, null);
        }

        PositionType positionType = (item.getPositionType() != null) ? item.getPositionType() : PositionType.LOCATION;
        DestinationMappingService.RushPriority initialRush = destinationMappingService.evaluateRush(
                RuleFieldProjection.itemRootFields(item), item.getProperties(), destinations, item.getPriority(), timestamp);
        com.flunav.backend.domain.Item routingItem = new com.flunav.backend.domain.Item(
                item.getId(), item.getName(), Boolean.TRUE.equals(item.getActive()), initialRush.effectivePriority(),
                item.getProperties());
        routingItem.setDestinations(destinations);
        RoutingDecisionService.RoutingDecision decision = routingDecisionService.selectRoute(
                routingItem, item.getLocationId(), positionType, timestamp);
        item.setSelectedExitId(decision.selectedExitId());
        item.setRoutingStatus(decision.routingStatus());
        item.setRoutingStatusUpdatedAt(timestamp);
        item.setPath(decision.path());
        return new AppliedDestination(destinations, decision.selectedExitId(), decision.routingStatus(),
                decision.path());
    }

    /**
     * Publishes the operational command that tells external equipment the selected
     * exit. Simulation and non-broadcast reductions skip this because command
     * messages are live side effects, not replayable domain history.
     */
    void publishDestinationCommandIfNeeded(ItemCreatedEvent event, ItemInput item,
            AppliedDestination appliedDestination, boolean shouldBroadcast) {
        if (!manageLogic || !shouldBroadcast || DatabaseContextHolder.getSimulationId() != null) {
            return;
        }
        if (appliedDestination.selectedExitId() == null
                || !Objects.equals(item.getSelectedExitId(), appliedDestination.selectedExitId())) {
            return;
        }

        try {
            amqpTemplate.convertAndSend(commandsQueue,
                    new ItemDestinationEvent(event.getEntityId(), appliedDestination.selectedExitId(),
                            event.getTimestamp()));
        } catch (Exception e) {
            logger.error("Failed to publish destination command for item {}", event.getEntityId(), e);
        }
    }

    /**
     * Normalizes externally supplied destination ids while preserving order.
     * Blank values are rejected before they can enter Redis routing state.
     */
    List<String> normalizeDestinations(List<String> destinations) {
        if (destinations == null || destinations.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String destination : destinations) {
            if (destination == null || destination.isBlank()) {
                throw new IllegalArgumentException("destinations must contain nonblank values");
            }
            normalized.add(destination.trim());
        }
        return List.copyOf(normalized);
    }

    private record AppliedDestination(
            List<String> destinations,
            String selectedExitId,
            RoutingStatus routingStatus,
            List<String> path) {
    }

    /**
     * Validates the normalized priority score accepted by routing.
     * The 0.0 to 1.0 range is used directly by capacity and travel-time scoring.
     */
    void validatePriority(Double priority) {
        if (priority == null || !Double.isFinite(priority) || priority < 0.0 || priority > 1.0) {
            throw new IllegalArgumentException("priority must be finite and between 0.0 and 1.0");
        }
    }

    /**
     * Prevents custom properties from redefining top-level item priority.
     * Priority is a first-class routing field, so allowing a duplicate property
     * would make display rules and route selection disagree.
     */
    void validateItemProperties(Map<String, Object> properties) {
        if (properties != null
                && properties.keySet().stream().anyMatch(key -> key != null && key.equalsIgnoreCase("priority"))) {
            throw new IllegalArgumentException("priority is a top-level item field");
        }
    }
}
