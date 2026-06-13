package com.flunav.backend.services;

import com.flunav.backend.domain.Conveyor;
import com.flunav.backend.domain.Location;
import com.flunav.backend.context.MdcContext;
import com.flunav.backend.exception.DuplicateItemException;
import com.flunav.backend.models.UpdateModel;
import com.flunav.backend.models.input.ItemInput;
import com.flunav.backend.models.input.LocationInput;
import com.flunav.backend.models.response.ConveyorResponse;
import com.flunav.backend.models.response.ItemResponse;
import com.flunav.backend.repositories.LiveItemRepository;
import com.flunav.backend.repositories.LiveConveyorRepository;
import com.flunav.backend.repositories.LiveLocationRepository; // 1. IMPORT
import com.orientechnologies.orient.core.exception.OConcurrentModificationException;
import flunav.context.UserContextHolder;
import flunav.events.*;
import flunav.types.LocationType;
import flunav.types.PositionType;
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
    private final AmqpTemplate amqpTemplate;
    private final String itemEventsRoutingKey;
    private final String commandsQueue;
    private final TimeService timeService;
    private final TopologyProvider topologyProvider;
    private final boolean manageLogic;
    private final LocationService locationService;
    private final ItemMovementProcessor itemMovementProcessor;
    private final DestinationMappingService destinationMappingService;

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
            LocationService locationService,
            AmqpTemplate amqpTemplate,
            @Value("${rabbitmq.routing-key.item-events}") String itemEventsRoutingKey,
            @Value("${rabbitmq.queue.commands}") String commandsQueue,
            TimeService timeService,
            TopologyProvider topologyProvider,
            ItemMovementProcessor itemMovementProcessor,
            DestinationMappingService destinationMappingService,
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
        this.amqpTemplate = amqpTemplate;
        this.itemEventsRoutingKey = itemEventsRoutingKey;
        this.commandsQueue = commandsQueue;
        this.timeService = timeService;
        this.topologyProvider = topologyProvider;
        this.itemMovementProcessor = itemMovementProcessor;
        this.destinationMappingService = destinationMappingService;
        this.manageLogic = manageLogic;
    }

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
                if (simulationId == null) {
                    clickHouseService.saveEventAsync(event);
                }

                Map<String, Object> resultMap = processEvent(event, shouldBroadcast);
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
            return switch (event) {
                case ItemCreatedEvent e -> {
                    try {
                        var item = new ItemInput(e);
                        AppliedDestination appliedDestination = applyDestinationToCreatedItem(item, e.getTimestamp());
                        itemService.createItem(item);
                        publishDestinationCommandIfNeeded(e, item, appliedDestination, shouldBroadcast);

                        if (item.getLocationId() != null) {
                            var positionType = topologyProvider.getPositionType(item.getLocationId());
                            if (positionType == PositionType.CONVEYOR) {
                                liveConveyorRepository.addItemToConveyor(item.getLocationId(), e.getEntityId(),
                                        e.getTimestamp());
                                itemMovementProcessor.handleItemEntryToConveyor(e.getEntityId(), item.getLocationId(),
                                        e.getTimestamp(),
                                        e.getProgress(), null);
                            } else {
                                itemMovementProcessor.processLocationEntry(e.getEntityId(), item.getLocationId(),
                                        e.getTimestamp());
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
                    yield Map.of("status", "PROCESSED_SUCCESSFULLY", "fieldName", e.getFieldName());
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
                                previousPosId);
                    } else {
                        itemMovementProcessor.processLocationEntry(e.getEntityId(), e.getLocationId(),
                                e.getTimestamp());
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

                    List<String> calculatedPath = pathfindingService.calculateShortestPath(
                            item.getPositionId(),
                            item.getPositionType(),
                            e.getLocationId());

                    item.setDestinationId(e.getLocationId());
                    item.setPath(calculatedPath);
                    var oldEvent = itemMovementProcessor.getScheduledEvent(item.getId());
                    itemMovementProcessor.cancelScheduledEvent(item.getId());
                    itemMovementProcessor
                            .scheduleEvent(new ItemPositionChangedEvent(item.getId(), calculatedPath.getFirst(), 0.0,
                                    oldEvent.getTimestamp()));
                    itemService.fullUpdateItem(item);

                    if (shouldBroadcast) {
                        webSocketService.broadcastItemUpdated(new UpdateModel(item.getId(),
                                Map.of("destination", e.getLocationId(), "path", calculatedPath)), e.getTimestamp());
                    }
                    yield Map.of("status", "PROCESSED_SUCCESSFULLY");
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
                    location.updateCapacity(e.getCapacity());
                    var updateModel = new UpdateModel(location.getId(), Map.of("capacity", location.getCapacity()));
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
                        liveItemRepository.deleteItem(item);

                    }
                    if (shouldBroadcast)
                        webSocketService.broadcastChuteEmptied(e.getEntityId(), e.getTimestamp());

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
                        webSocketService.broadcastConnectionUpdated(
                                new UpdateModel(conveyor.getId(),
                                        Map.of("properties", e.getUpdatedProperties(), "customColor",
                                                this.displayRulesService.applyDisplayRules(conveyor.getProperties(),
                                                        this.displayRulesService.getDisplayRules()))),
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
        });
    }

    private void checkpointItems(String edgeId, double oldSpeed, Instant timestamp) {
        itemMovementProcessor.checkpointItems(edgeId, oldSpeed, timestamp);
    }

    private AppliedDestination applyDestinationToCreatedItem(ItemInput item, Instant timestamp) {
        String explicitDestinationId = normalizeDestination(item.getDestinationId());
        boolean explicitDestination = explicitDestinationId != null;
        String destinationId = explicitDestination
                ? explicitDestinationId
                : destinationMappingService.resolveDestination(item.getProperties(), timestamp).orElse(null);

        if (destinationId == null) {
            return AppliedDestination.none();
        }

        if (topologyProvider.getLocationById(destinationId) == null) {
            logger.warn("Mapped destination does not exist destination={}", destinationId);
            if (explicitDestination) {
                item.setDestinationId(destinationId);
                return new AppliedDestination(destinationId, false, true);
            }
            return AppliedDestination.none();
        }

        item.setDestinationId(destinationId);

        if (item.getLocationId() == null) {
            logger.warn("Cannot calculate destination path for item {} because locationId is missing", item.getId());
            return new AppliedDestination(destinationId, !explicitDestination, true);
        }

        PositionType positionType = (item.getPositionType() != null) ? item.getPositionType() : PositionType.LOCATION;
        List<String> calculatedPath = pathfindingService.calculateShortestPath(
                item.getLocationId(),
                positionType,
                destinationId);

        if (!calculatedPath.isEmpty()) {
            item.setPath(calculatedPath);
        }

        return new AppliedDestination(destinationId, !explicitDestination, true);
    }

    private void publishDestinationCommandIfNeeded(ItemCreatedEvent event, ItemInput item,
            AppliedDestination appliedDestination, boolean shouldBroadcast) {
        if (!manageLogic || !shouldBroadcast || DatabaseContextHolder.getSimulationId() != null) {
            return;
        }
        if (!appliedDestination.fromMapping() || !appliedDestination.applied()) {
            return;
        }
        if (!Objects.equals(item.getDestinationId(), appliedDestination.destinationId())) {
            return;
        }

        try {
            amqpTemplate.convertAndSend(commandsQueue,
                    new ItemDestinationEvent(event.getEntityId(), appliedDestination.destinationId(),
                            event.getTimestamp()));
        } catch (Exception e) {
            logger.error("Failed to publish destination command for item {}", event.getEntityId(), e);
        }
    }

    private String normalizeDestination(String destinationId) {
        if (destinationId == null || destinationId.isBlank()) {
            return null;
        }
        return destinationId.trim();
    }

    private record AppliedDestination(String destinationId, boolean fromMapping, boolean applied) {
        private static AppliedDestination none() {
            return new AppliedDestination(null, false, false);
        }
    }
}
