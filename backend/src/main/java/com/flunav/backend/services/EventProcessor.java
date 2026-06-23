package com.flunav.backend.services;

import com.flunav.backend.domain.Conveyor;
import com.flunav.backend.domain.Location;
import com.flunav.backend.context.MdcContext;
import com.flunav.backend.exception.DuplicateItemException;
import com.flunav.backend.models.UpdateModel;
import com.flunav.backend.models.input.ItemInput;
import com.flunav.backend.models.input.LocationInput;
import com.flunav.backend.models.response.ConveyorResponse;
import com.flunav.backend.models.response.DisplayRuleColorResult;
import com.flunav.backend.models.response.ItemResponse;
import com.flunav.backend.repositories.LiveItemRepository;
import com.flunav.backend.repositories.LiveConveyorRepository;
import com.flunav.backend.repositories.LiveLocationRepository; // 1. IMPORT
import com.flunav.backend.utils.PriorityScoreUtils;
import com.orientechnologies.orient.core.exception.OConcurrentModificationException;
import flunav.context.UserContextHolder;
import flunav.events.*;
import flunav.types.LocationType;
import flunav.types.PositionType;
import flunav.types.RoutingStatus;
import jakarta.annotation.PreDestroy;

import org.modelmapper.ModelMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AmqpTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import com.flunav.backend.context.DatabaseContextHolder;

@Service
public class EventProcessor {
    private static final Logger logger = LoggerFactory.getLogger(EventProcessor.class);

    private final ClickHouseService clickHouseService;
    private final ItemService itemService;
    private final ConveyorService conveyorService;
    private final WebSocketService webSocketService;
    private final PathfindingService pathfindingService;
    private final LiveItemRepository liveItemRepository;
    private final LiveConveyorRepository liveConveyorRepository;
    private final LiveLocationRepository liveLocationRepository; // 2. INJECT
    private final SimulationService simulationService;
    private final LiveSystemScheduler liveSystemScheduler;
    private final DisplayRulesService displayRulesService;
    private final GraphService graphService;
    private final AmqpTemplate amqpTemplate;
    private final String itemEventsRoutingKey;
    private final String commandsQueue;
    private final TimeService timeService;
    private final TopologyProvider topologyProvider;
    private final boolean manageLogic;
    private final LocationService locationService;
    private final ItemMovementProcessor itemMovementProcessor;
    private final DestinationMappingService destinationMappingService;
    private final DestinationExitMappingService destinationExitMappingService;
    private final RoutingDecisionService routingDecisionService;
    private final RoutingCoordinator routingCoordinator;
    private final PathAssignmentPublisher pathAssignmentPublisher;
    private final ThroughputBucketService throughputBucketService;

    ModelMapper modelMapper = new ModelMapper();

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ConcurrentMap<String, CompletableFuture<Void>> processingFutures = new ConcurrentHashMap<>();

    public EventProcessor(
            ClickHouseService clickHouseService,
            ItemService itemService,
            ConveyorService conveyorService,
            WebSocketService webSocketService,
            PathfindingService pathfindingService,
            LiveItemRepository liveItemRepository,
            LiveConveyorRepository liveConveyorRepository,
            LiveLocationRepository liveLocationRepository, // 3. INJECT
            @Lazy SimulationService simulationService,
            LiveSystemScheduler liveSystemScheduler,
            DisplayRulesService displayRulesService,
            @Lazy GraphService graphService,
            LocationService locationService,
            AmqpTemplate amqpTemplate,
            @Value("${rabbitmq.routing-key.item-events}") String itemEventsRoutingKey,
            @Value("${rabbitmq.queue.commands}") String commandsQueue,
            TimeService timeService,
            TopologyProvider topologyProvider,
            ItemMovementProcessor itemMovementProcessor,
            DestinationMappingService destinationMappingService,
            DestinationExitMappingService destinationExitMappingService,
            RoutingDecisionService routingDecisionService,
            RoutingCoordinator routingCoordinator,
            PathAssignmentPublisher pathAssignmentPublisher,
            ThroughputBucketService throughputBucketService,
            @Value("${simulation.manage-logic:true}") boolean manageLogic) {
        this.clickHouseService = clickHouseService;
        this.itemService = itemService;
        this.conveyorService = conveyorService;
        this.webSocketService = webSocketService;
        this.pathfindingService = pathfindingService;
        this.liveItemRepository = liveItemRepository;
        this.liveConveyorRepository = liveConveyorRepository;
        this.liveLocationRepository = liveLocationRepository; // 4. INJECT
        this.simulationService = simulationService;
        this.locationService = locationService;
        this.liveSystemScheduler = liveSystemScheduler;
        this.displayRulesService = displayRulesService;
        this.graphService = graphService;
        this.amqpTemplate = amqpTemplate;
        this.itemEventsRoutingKey = itemEventsRoutingKey;
        this.commandsQueue = commandsQueue;
        this.timeService = timeService;
        this.topologyProvider = topologyProvider;
        this.itemMovementProcessor = itemMovementProcessor;
        this.destinationMappingService = destinationMappingService;
        this.destinationExitMappingService = destinationExitMappingService;
        this.routingDecisionService = routingDecisionService;
        this.routingCoordinator = routingCoordinator;
        this.pathAssignmentPublisher = pathAssignmentPublisher;
        this.throughputBucketService = throughputBucketService;
        this.manageLogic = manageLogic;
    }

    /**
     * Processes an event while preserving per-entity ordering.
     * Entity events are chained by id so unrelated items can run concurrently but a
     * single item's history is reduced in timestamp/order arrival sequence.
     */
    public CompletableFuture<Map<String, Object>> process(DomainEvent event, boolean shouldBroadcast) {
        final String entityId = (event instanceof EntityEvent e) ? e.getEntityId() : null;

        if (entityId == null) {
            return executeOn(event, shouldBroadcast, executor);
        }

        CompletableFuture<Map<String, Object>> taskResultFuture = new CompletableFuture<>();

        processingFutures.compute(entityId, (id, previousTaskCompletion) -> {
            Supplier<CompletableFuture<Map<String, Object>>> workSupplier = () -> executeOn(event, shouldBroadcast,
                    executor);

            if (previousTaskCompletion == null || previousTaskCompletion.isDone()) {
                workSupplier.get().whenComplete((result, error) -> {
                    if (error != null)
                        taskResultFuture.completeExceptionally(error);
                    else
                        taskResultFuture.complete(result);
                });
            } else {
                previousTaskCompletion.whenComplete((ignored, throwable) -> {
                    workSupplier.get().whenComplete((result, error) -> {
                        if (error != null)
                            taskResultFuture.completeExceptionally(error);
                        else
                            taskResultFuture.complete(result);
                    });
                });
            }
            CompletableFuture<Void> chainedFuture = taskResultFuture.handle((result, error) -> null);
            chainedFuture.whenComplete((ignored, error) -> processingFutures.remove(entityId, chainedFuture));
            return chainedFuture;
        });

        return taskResultFuture;
    }

    /**
     * Chooses synchronous or async execution based on simulation context.
     * Simulation replay stays on the current thread so ThreadLocal simulation and
     * virtual-time state cannot be lost across an executor boundary.
     */
    private CompletableFuture<Map<String, Object>> executeOn(DomainEvent event, boolean shouldBroadcast,
            Executor executor) {
        final String currentSimId = DatabaseContextHolder.getSimulationId();
        final String currentSenderId = UserContextHolder.getSenderId();

        if (currentSimId != null) {
            try {
                return CompletableFuture
                        .completedFuture(executeBusinessLogic(event, shouldBroadcast, currentSimId, currentSenderId));
            } catch (Exception e) {
                return CompletableFuture.failedFuture(e);
            }
        }

        return CompletableFuture.supplyAsync(
                () -> executeBusinessLogic(event, shouldBroadcast, currentSimId, currentSenderId), executor);
    }

    /**
     * Enters the context needed to reduce one event into derived state.
     * Live events are persisted to ClickHouse around processing according to their
     * replay needs, while simulation events update only isolated derived stores.
     */
    private Map<String, Object> executeBusinessLogic(DomainEvent event, boolean shouldBroadcast, String simulationId,
            String senderId) {
        String entityId = event instanceof EntityEvent entityEvent ? entityEvent.getEntityId() : null;
        String mode = simulationId == null ? "LIVE" : "SIMULATION";
        String effectiveSenderId = event.getSenderId() != null ? event.getSenderId() : senderId;
        long startedAt = System.nanoTime();

        try (var simulationContext = DatabaseContextHolder.enterSimulationContext(simulationId);
                var senderContext = UserContextHolder.enterSenderContext(effectiveSenderId);
                var eventContext = MdcContext.withValues(Map.of(
                        "event_type", event.getEventType(),
                        "event_id", event.getEventId(),
                        "entity_id", entityId == null ? "" : entityId,
                        "mode", mode))) {
            logProcessingStarted(shouldBroadcast);

            try {
                boolean persistAfterProcessing = event instanceof MapDestinationsEvent
                        || event instanceof MapDestinationExitsEvent
                        || event instanceof MapDisplayRulesEvent;
                if (simulationId == null && !persistAfterProcessing) {
                    clickHouseService.saveEventAsync(event);
                }

                Map<String, Object> resultMap = processEvent(event, shouldBroadcast);
                if (simulationId == null && persistAfterProcessing) {
                    clickHouseService.saveEventAsync(event);
                }
                logProcessingCompleted(shouldBroadcast, elapsedMillis(startedAt));
                return resultMap;
            } catch (Exception e) {
                logProcessingFailed(shouldBroadcast, elapsedMillis(startedAt), e);
                throw new CompletionException(e);
            }
        }
    }

    private void logProcessingStarted(boolean shouldBroadcast) {
        logger.atDebug()
                .addKeyValue("broadcast", shouldBroadcast)
                .log("Event processing started");
    }

    private void logProcessingCompleted(boolean shouldBroadcast, long durationMillis) {
        logger.atDebug()
                .addKeyValue("broadcast", shouldBroadcast)
                .addKeyValue("duration_ms", durationMillis)
                .log("Event processing completed");
    }

    private void logProcessingFailed(boolean shouldBroadcast, long durationMillis, Exception error) {
        logger.atError()
                .addKeyValue("broadcast", shouldBroadcast)
                .addKeyValue("duration_ms", durationMillis)
                .setCause(error)
                .log("Event processing failed");
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    private <T> T executeWithRetry(Supplier<T> operation) {
        final int MAX_RETRIES = 5;
        int attempt = 0;
        while (true) {
            try {
                return operation.get();
            } catch (OConcurrentModificationException | java.util.NoSuchElementException e) {
                attempt++;
                if (attempt >= MAX_RETRIES)
                    throw e;
                try {
                    Thread.sleep(50 * (long) Math.pow(2, attempt - 1));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Retry interrupted", ie);
                }
            }
        }
    }

    public Map<String, Object> processEvent(DomainEvent event) {
        return processEvent(event, true);
    }

    public Map<String, Object> processEventWithoutBroadcast(DomainEvent event) {
        return processEvent(event, false);
    }

    public Map<String, Object> processEvent(DomainEvent event, boolean shouldBroadcast) {
        UserContextHolder.setSenderId(event.getSenderId());
        return this.<Map<String, Object>>executeWithRetry(() -> {
            long chuteItemsExited = event instanceof ChuteEmptyEvent e
                    ? Optional.ofNullable(liveLocationRepository.getItemsAtLocation(e.getEntityId()))
                            .map(Set::size)
                            .orElse(0)
                    : 0;

            Map<String, Object> result = switch (event) {
                case ItemCreatedEvent e -> {
                    try {
                        var item = new ItemInput(e);
                        AppliedDestination appliedDestination = routingCoordinator.withRoutingLock(() -> {
                            AppliedDestination destination = applyDestinationToCreatedItem(item, e.getTimestamp());
                            itemService.createItem(item);
                            return destination;
                        });
                        publishDestinationCommandIfNeeded(e, item, appliedDestination, shouldBroadcast);
                        pathAssignmentPublisher.publishIfAssigned(
                                item.getId(),
                                appliedDestination.selectedExitId(),
                                appliedDestination.routingStatus(),
                                appliedDestination.path(),
                                e.getTimestamp(),
                                shouldBroadcast);

                        if (item.getLocationId() != null) {
                            var positionType = topologyProvider.getPositionType(item.getLocationId());
                            if (positionType == PositionType.CONVEYOR) {
                                liveConveyorRepository.addItemToConveyor(item.getLocationId(), e.getEntityId(),
                                        e.getTimestamp());
                                itemMovementProcessor.handleItemEntryToConveyor(e.getEntityId(), item.getLocationId(),
                                        e.getTimestamp(),
                                        e.getProgress(), null, shouldBroadcast);
                            } else {
                                itemMovementProcessor.processLocationEntry(e.getEntityId(), item.getLocationId(),
                                        e.getTimestamp(), shouldBroadcast);
                            }
                        }

                        if (shouldBroadcast) {
                            ItemResponse response = modelMapper.map(item, ItemResponse.class);
                            response.setCustomColor(this.displayRulesService.applyDisplayRules(item.getProperties(),
                                    this.displayRulesService.getDisplayRules()));
                            webSocketService.broadcastItemCreated(response, e.getTimestamp());
                        }
                        yield Map.of("status", "CREATED", "itemId", e.getEntityId());
                    } catch (DuplicateItemException die) {
                        logger.warn("Received a duplicate ItemCreatedEvent for existing item '{}'. Ignoring event.",
                                e.getEntityId());
                        yield Map.of("status", "IGNORED_DUPLICATE", "itemId", e.getEntityId());
                    }
                }

                case MapDestinationsEvent e -> {
                    destinationMappingService.saveMapDestinations(e);
                    yield Map.of("status", "PROCESSED_SUCCESSFULLY");
                }

                case MapDestinationExitsEvent e -> {
                    destinationExitMappingService.saveMappings(e);
                    retryWaitingHighPriorityItems(e.getTimestamp(), shouldBroadcast);
                    yield Map.of("status", "PROCESSED_SUCCESSFULLY");
                }

                case MapDisplayRulesEvent e -> {
                    displayRulesService.updateDisplayRules(e.getRules());
                    yield Map.of("status", "PROCESSED_SUCCESSFULLY");
                }

                case ItemPositionChangedEvent e -> {
                    var positionType = topologyProvider.getPositionType(e.getLocationId());

                    var lastState = liveItemRepository.getItemState(e.getEntityId());
                    String previousPosId = (lastState != null) ? lastState.getPositionId() : null;
                    var lastPositionType = (lastState != null) ? lastState.getType() : null;

                    // If we are moving to a location (conveyor or node) that is NOT connected to
                    // the previous one,
                    // we consider it a teleport.
                    boolean isTeleport = false;
                    if (previousPosId != null && lastPositionType != null) {
                        isTeleport = !pathfindingService.arePositionsConnected(previousPosId, lastPositionType,
                                e.getLocationId(), positionType);
                    }

                    // --- REMOVE FROM PREVIOUS POSITION ---
                    if (previousPosId != null) {
                        if (PositionType.CONVEYOR.equals(lastPositionType)) {
                            liveConveyorRepository.removeItemFromConveyor(previousPosId, e.getEntityId());
                        } else {
                            var previousLocation = topologyProvider.getLocationById(previousPosId);
                            // Only remove if it was a buffer location
                            if (previousLocation != null && previousLocation.getType() == LocationType.CHUTE) {
                                liveLocationRepository.removeItemFromLocation(previousPosId, e.getEntityId());
                            }
                        }
                    }

                    if (isTeleport && previousPosId != null && lastPositionType != null) {
                        // TODO: record into ch?
                    }

                    itemService.updateItemPosition(e.getEntityId(), e.getLocationId(), positionType, e.getTimestamp(),
                            e.getProgress(), null);

                    // --- ADD TO NEW POSITION ---
                    if (positionType == PositionType.CONVEYOR) {
                        liveConveyorRepository.addItemToConveyor(e.getLocationId(), e.getEntityId(), e.getTimestamp());
                        itemMovementProcessor.handleItemEntryToConveyor(e.getEntityId(), e.getLocationId(),
                                e.getTimestamp(), e.getProgress(),
                                previousPosId, shouldBroadcast);
                    } else {
                        itemMovementProcessor.processLocationEntry(e.getEntityId(), e.getLocationId(),
                                e.getTimestamp(), shouldBroadcast);
                    }

                    if (shouldBroadcast) {
                        webSocketService.broadcastPositionUpdate(e.getEntityId(), e.getLocationId(), e.getTimestamp(),
                                positionType, e.getProgress());
                    }

                    yield Map.of("status", "PROCESSED_SUCCESSFULLY");
                }

                case ItemPositionDeletedEvent e -> {
                    var lastState = liveItemRepository.getItemState(e.getEntityId());
                    // This logic is now handled by liveItemRepository.deleteItem(), which is more
                    // robust
                    itemMovementProcessor.cancelScheduledEvent(e.getEntityId());
                    itemService.deleteItem(e.getEntityId());
                    if (shouldBroadcast)
                        webSocketService.broadcastPositionLost(e.getEntityId(), e.getTimestamp());
                    yield Map.of("status", "PROCESSED_SUCCESSFULLY");
                }

                case ItemRenamedEvent e -> {
                    var item = itemService.getItemById(e.getEntityId());
                    itemService.updateItem(new UpdateModel(item.getId(), Map.of("name", e.getNewName())));
                    if (shouldBroadcast)
                        webSocketService.broadcastItemUpdated(
                                new UpdateModel(item.getId(), Map.of("name", e.getNewName())), e.getTimestamp());
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
                    itemService.updateItem(new UpdateModel(item.getId(), Map.of("active", false)));
                    itemMovementProcessor.cancelScheduledEvent(e.getEntityId());
                    if (shouldBroadcast) {
                        webSocketService.broadcastItemUpdated(
                                new UpdateModel(item.getId(), Map.of("active", false)), e.getTimestamp());
                    }
                    yield Map.of("status", "PROCESSED_SUCCESSFULLY");
                }

                case ItemActivatedEvent e -> {
                    var item = itemService.getItemById(e.getEntityId());
                    itemService.updateItem(new UpdateModel(item.getId(), Map.of("active", true)));
                    if (shouldBroadcast) {
                        webSocketService.broadcastItemUpdated(
                                new UpdateModel(item.getId(), Map.of("active", true)), e.getTimestamp());
                    }
                    yield Map.of("status", "PROCESSED_SUCCESSFULLY");
                }

                case ItemPropertiesUpdatedEvent e -> {
                    var item = itemService.getItemById(e.getEntityId());
                    item.updateProperties(e);
                    Map<String, Object> map = new HashMap<>();
                    map.put("properties", item.getProperties());
                    var updateModel = new UpdateModel(item.getId(), map);
                    itemService.updateItem(updateModel);
                    if (shouldBroadcast) {
                        map.put("customColor", this.displayRulesService.applyDisplayRules(item.getProperties(),
                                this.displayRulesService.getDisplayRules()));
                        webSocketService.broadcastItemUpdated(updateModel, e.getTimestamp());
                    }
                    yield Map.of("status", "PROCESSED_SUCCESSFULLY");
                }

                case ItemDeletedEvent e -> {
                    var lastState = liveItemRepository.getItemState(e.getEntityId());
                    if (lastState != null) {
                        // TODO: do we really need to fire these events for analytics?
                        if (lastState.getPositionId() != null && lastState.getType() != null) {
                            itemMovementProcessor
                                    .publishEvent(new PathTraversedEvent(e.getEntityId(), lastState.getPositionId(),
                                            lastState.getType(), lastState.getPositionId(),
                                            lastState.getType(), List.of(lastState.getPositionId())));
                        }
                    }
                    itemMovementProcessor.cancelScheduledEvent(e.getEntityId());
                    itemService.deleteItem(e.getEntityId());
                    liveItemRepository.deleteItem(e.getEntityId());

                    if (shouldBroadcast)
                        webSocketService.broadcastItemDeleted(e.getEntityId(), e.getTimestamp());
                    yield Map.of("status", "PROCESSED_SUCCESSFULLY");
                }

                case ItemDestinationEvent e -> {
                    var item = itemService.getItemById(e.getEntityId());
                    if (item == null) {
                        throw new IllegalArgumentException("Item does not exist: " + e.getEntityId());
                    }
                    RoutingDecisionService.RoutingDecision decision = item.getPositionId() == null
                            ? RoutingDecisionService.RoutingDecision.none()
                            : routingCoordinator.withRoutingLock(() -> {
                                RoutingDecisionService.RoutingDecision selected =
                                        routingDecisionService.selectRouteToExit(
                                                item,
                                                item.getPositionId(),
                                                item.getPositionType() != null ? item.getPositionType()
                                                        : PositionType.LOCATION,
                                                e.getLocationId());
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


                    if (shouldBroadcast) {
                        Map<String, Object> updates = new HashMap<>();
                        updates.put("selectedExitId", decision.selectedExitId());
                        updates.put("routingStatus", decision.routingStatus());
                        updates.put("routingStatusUpdatedAt", e.getTimestamp());
                        updates.put("path", decision.path());
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

                    RoutingDecisionService.RoutingDecision decision = routingCoordinator.withRoutingLock(() -> {
                        RoutingDecisionService.RoutingDecision selected = routingDecisionService.selectRoute(
                                item, e.getDecisionPointId(), PositionType.LOCATION);
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

                    if (shouldBroadcast) {
                        Map<String, Object> updates = new HashMap<>();
                        updates.put("selectedExitId", decision.selectedExitId());
                        updates.put("routingStatus", decision.routingStatus());
                        updates.put("routingStatusUpdatedAt", e.getTimestamp());
                        updates.put("path", decision.path());
                        webSocketService.broadcastItemUpdated(new UpdateModel(item.getId(), updates), e.getTimestamp());
                    }

                    yield Map.of(
                            "status", "PROCESSED_SUCCESSFULLY",
                            "nextConveyorId", Objects.toString(decision.nextConveyorId(), ""),
                            "selectedExitId", Objects.toString(decision.selectedExitId(), ""));
                }

                case ItemPathChangedEvent e -> {
                    itemService.updateItemPath(e.getEntityId(), e.getPath());
                    if (shouldBroadcast) {
                        webSocketService.broadcastItemUpdated(
                                new UpdateModel(e.getEntityId(), Map.of("path", e.getPath())), e.getTimestamp());
                    }
                    yield Map.of("status", "PROCESSED_SUCCESSFULLY");
                }

                // --- LOCATION EVENTS (Nodes) ---

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
                        map.put("customColor", this.displayRulesService.applyDisplayRules(location.getProperties(),
                                this.displayRulesService.getDisplayRules()));
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
                        retryWaitingHighPriorityItems(e.getTimestamp(), shouldBroadcast);
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
                            e.getIsActive());
                    if (shouldBroadcast) {
                        String customColor = this.displayRulesService.applyDisplayRules(e.getProperties(),
                                this.displayRulesService.getDisplayRules());
                        webSocketService.broadcastConnectionCreated(new ConveyorResponse(e.getConnectionId(),
                                e.getSourceId(), e.getTargetId(), e.getName(), e.getLength(), e.getSpeed(),
                                e.getMinDistance(),
                                e.getType(),
                                e.getIsActive(), e.getMainPath(), e.getCapacity(),
                                e.getProperties(), customColor), e.getTimestamp());
                    }
                    yield Map.of("status", "PROCESSED_SUCCESSFULLY");
                }

                case ConnectionSpeedChangedEvent e -> {
                    var conveyor = topologyProvider.getConveyorById(e.getEntityId());
                    if (conveyor != null) {
                        checkpointItems(e.getEntityId(), conveyor.getSpeed(), e.getTimestamp());
                        double oldSpeed = conveyor.getSpeed();
                        conveyor.setSpeed(e.getSpeed());
                        conveyorService.updateConveyor(conveyor);
                        if (manageLogic && oldSpeed <= 0 && e.getSpeed() > 0) {
                            itemMovementProcessor.recalculateConveyorAccumulation(e.getEntityId());
                            itemMovementProcessor.wakeUpPrecedingConveyors(conveyor.getSourceLocationId());
                        }
                        if (shouldBroadcast)
                            webSocketService.broadcastConnectionUpdated(
                                    new UpdateModel(conveyor.getId(), Map.of("speed", e.getSpeed())), e.getTimestamp());
                    }
                    yield Map.of("status", "PROCESSED_SUCCESSFULLY");
                }

                case ChuteEmptyEvent e -> {
                    // Correctly clear items from the chute location in Redis
                    Set<String> items = liveLocationRepository.getItemsAtLocation(e.getEntityId());
                    for (String item : items) {
                        liveLocationRepository.removeItemFromLocation(e.getEntityId(), item);
                        liveItemRepository.deleteItem(item);

                    }
                    if (shouldBroadcast)
                        webSocketService.broadcastChuteEmptied(e.getEntityId(), e.getTimestamp());

                    retryWaitingHighPriorityItems(e.getTimestamp(), shouldBroadcast);
                    if (manageLogic) {
                        itemMovementProcessor.wakeUpPrecedingConveyors(e.getEntityId());
                    }
                    yield Map.of("status", "PROCESSED_SUCCESSFULLY");
                }

                case ConnectionLengthChangedEvent e -> {
                    var conveyor = conveyorService.getConveyorById(e.getEntityId());
                    conveyor.setLength(e.getLength());
                    conveyorService.updateConveyor(conveyor);

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
                        var customColor = this.displayRulesService.applyDisplayRules(
                                conveyor.getProperties(),
                                this.displayRulesService.getDisplayRules());

                        Map<String, Object> updates = new HashMap<>();
                        updates.put("properties", e.getUpdatedProperties());
                        updates.put("customColor", customColor);

                        webSocketService.broadcastConnectionUpdated(
                                new UpdateModel(conveyor.getId(), updates),
                                e.getTimestamp());
                    }

                    yield Map.of("status", "PROCESSED_SUCCESSFULLY");
                }

                case ConnectionActivatedEvent e -> {
                    var conveyor = conveyorService.getConveyorById(e.getEntityId());
                    conveyor.setActive(true);
                    conveyorService.updateConveyor(conveyor);
                    if (shouldBroadcast) {
                        webSocketService.broadcastConnectionUpdated(
                                new UpdateModel(conveyor.getId(), Map.of("active", true)), e.getTimestamp());
                    }
                    yield Map.of("status", "PROCESSED_SUCCESSFULLY");
                }

                case ConnectionDeactivatedEvent e -> {
                    var conveyor = conveyorService.getConveyorById(e.getEntityId());
                    conveyor.setActive(false);
                    conveyorService.updateConveyor(conveyor);
                    if (shouldBroadcast) {
                        webSocketService.broadcastConnectionUpdated(
                                new UpdateModel(conveyor.getId(), Map.of("active", false)), e.getTimestamp());
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
                    conveyorService.deleteConveyor(e.getSourceLocationId(), e.getTargetLocationId());
                    if (shouldBroadcast) {
                        webSocketService.broadcastConnectionDeleted(e.getSourceLocationId(), e.getTargetLocationId(),
                                e.getTimestamp());
                    }
                    yield Map.of("status", "PROCESSED_SUCCESSFULLY");
                }

                case PathTraversedEvent e -> {
                    yield Map.of("status", "PROCESSED_SUCCESSFULLY");
                }

                default -> {
                    logger.debug("Event type {} handled by fallback logic or ignored",
                            event.getClass().getSimpleName());
                    yield Map.of("status", "PROCESSED_SUCCESSFULLY");
                }
            };
            if (shouldBroadcast) {
                throughputBucketService.recordSuccessfulReduction(event, result, chuteItemsExited);
            }
            return result;
        });
    }

    private void checkpointItems(String edgeId, double oldSpeed, Instant timestamp) {
        itemMovementProcessor.checkpointItems(edgeId, oldSpeed, timestamp);
    }

    private AppliedDestination applyDestinationToCreatedItem(ItemInput item, Instant timestamp) {
        List<String> explicitDestinations = normalizeDestinations(item.getDestinations());
        List<String> destinations = !explicitDestinations.isEmpty()
                ? explicitDestinations
                : destinationMappingService.resolveDestinations(item.getProperties(), timestamp);
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
        com.flunav.backend.domain.Item routingItem = new com.flunav.backend.domain.Item(
                item.getId(), item.getName(), Boolean.TRUE.equals(item.getActive()), item.getProperties());
        routingItem.setDestinations(destinations);
        RoutingDecisionService.RoutingDecision decision = routingDecisionService.selectRoute(
                routingItem, item.getLocationId(), positionType);
        item.setSelectedExitId(decision.selectedExitId());
        item.setRoutingStatus(decision.routingStatus());
        item.setRoutingStatusUpdatedAt(timestamp);
        item.setPath(decision.path());
        return new AppliedDestination(destinations, decision.selectedExitId(), decision.routingStatus(),
                decision.path());
    }

    private void publishDestinationCommandIfNeeded(ItemCreatedEvent event, ItemInput item,
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

    private void retryWaitingHighPriorityItems(Instant timestamp, boolean shouldBroadcast) {
        routingCoordinator.withRoutingLock(() -> {
            liveItemRepository.getAllActiveItems().stream()
                    .filter(item -> item != null)
                    .filter(item -> item.getRoutingStatus() == RoutingStatus.WAITING_FOR_CAPACITY)
                    .sorted(Comparator
                            .comparing(com.flunav.backend.models.RedisLiveItem::getRoutingStatusUpdatedAt,
                                    Comparator.nullsLast(Comparator.naturalOrder()))
                            .thenComparing(com.flunav.backend.models.RedisLiveItem::getEntryTime,
                                    Comparator.nullsLast(Comparator.naturalOrder()))
                            .thenComparing(com.flunav.backend.models.RedisLiveItem::getId))
                    .forEach(waitingState -> {
                        var item = itemService.getItemById(waitingState.getId());
                        if (item == null || PriorityScoreUtils.priorityScore(item.getProperties()) <= 0.0) {
                            return;
                        }
                        PositionType positionType = item.getPositionType() != null
                                ? item.getPositionType()
                                : PositionType.LOCATION;
                        RoutingDecisionService.RoutingDecision decision = routingDecisionService.selectRoute(
                                item, item.getPositionId(), positionType);

                        boolean changed = !Objects.equals(waitingState.getSelectedExitId(), decision.selectedExitId())
                                || !Objects.equals(waitingState.getPath(), decision.path())
                                || waitingState.getRoutingStatus() != decision.routingStatus();
                        if (!changed) {
                            return;
                        }

                        itemService.updateItemRouting(
                                item.getId(), item.getDestinations(), decision.selectedExitId(),
                                decision.routingStatus(), timestamp, decision.path());
                        pathAssignmentPublisher.publishIfAssigned(
                                item.getId(),
                                decision.selectedExitId(),
                                decision.routingStatus(),
                                decision.path(),
                                timestamp,
                                shouldBroadcast);

                        if (shouldBroadcast) {
                            Map<String, Object> updates = new HashMap<>();
                            updates.put("selectedExitId", decision.selectedExitId());
                            updates.put("routingStatus", decision.routingStatus());
                            updates.put("routingStatusUpdatedAt", timestamp);
                            updates.put("path", decision.path());
                            webSocketService.broadcastItemUpdated(new UpdateModel(item.getId(), updates), timestamp);
                        }
                    });
            return null;
        });
    }

    private List<String> normalizeDestinations(List<String> destinations) {
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
}
