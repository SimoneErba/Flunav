package com.flunav.backend.services;

import com.flunav.backend.domain.Conveyor;
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
    private final TimeService timeService;
    private final TopologyProvider topologyProvider;
    private final boolean manageLogic;
    private final LocationService locationService;

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
            TimeService timeService,
            TopologyProvider topologyProvider,
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
        this.timeService = timeService;
        this.topologyProvider = topologyProvider;
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
            return taskResultFuture.thenApply(v -> null);
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
        String existingSimId = DatabaseContextHolder.getSimulationId();
        try {
            if (simulationId != null && !simulationId.equals(existingSimId))
                DatabaseContextHolder.enterSimulationContext(simulationId);
            if (senderId != null)
                UserContextHolder.setSenderId(senderId);

            if (simulationId == null) {
                clickHouseService.saveEventAsync(event);
            }

            Map<String, Object> resultMap = processEvent(event, shouldBroadcast);
            logger.info("Successfully processed event: {}", event.getEventType());
            return resultMap;

        } catch (Exception e) {
            logger.error("Error processing event: {}", event.getEventType(), e);
            throw new CompletionException(e);
        } finally {
            if (simulationId != null && !simulationId.equals(existingSimId))
                DatabaseContextHolder.clearSimulation();
            UserContextHolder.clear();
        }
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
            logger.debug("Processing {}", event.getEventType());
            return switch (event) {
                case ItemCreatedEvent e -> {
                    var item = new ItemInput(e);
                    itemService.createItem(item);

                    if (item.getLocationId() != null) {
                        var positionType = topologyProvider.getPositionType(item.getLocationId());
                        if (positionType == PositionType.CONVEYOR) {
                            liveConveyorRepository.addItemToConveyor(item.getLocationId(), e.getEntityId(),
                                    e.getTimestamp());
                            handleItemEntryToConveyor(e.getEntityId(), item.getLocationId(), e.getTimestamp(),
                                    e.getProgress(), null);
                        } else {
                            var location = topologyProvider.getLocationById(item.getLocationId());
                            // Only store items in locations that act as buffers (e.g., Chutes)
                            if (location != null && location.getType() == LocationType.CHUTE) {
                                liveLocationRepository.addItemToLocation(item.getLocationId(), e.getEntityId());
                            }
                        }
                    }

                    if (shouldBroadcast) {
                        ItemResponse response = modelMapper.map(item, ItemResponse.class);
                        response.setCustomColor(this.displayRulesService.applyDisplayRules(item.getProperties(),
                                this.displayRulesService.getDisplayRules()));
                        webSocketService.broadcastItemCreated(response, e.getTimestamp());
                    }
                    yield Map.of("status", "CREATED", "itemId", e.getEntityId());
                }

                case ItemPositionChangedEvent e -> {
                    var positionType = topologyProvider.getPositionType(e.getLocationId());

                    var lastState = liveItemRepository.getItemState(e.getEntityId());
                    String previousPosId = lastState.getPositionId();
                    var lastPositionType = lastState.getType();

                    boolean isTeleport = previousPosId != null && e.getPreviousLocationId() != null
                            && !previousPosId.equals(e.getPreviousLocationId());

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
                        if (pathfindingService.arePositionsConnected(previousPosId, lastPositionType, e.getLocationId(),
                                positionType)) {
                            List<String> path = pathfindingService.calculateShortestPath(previousPosId,
                                    lastPositionType, e.getLocationId());
                            publishEvent(new PathTraversedEvent(e.getEntityId(), previousPosId, lastPositionType,
                                    e.getLocationId(), positionType, path));
                        }
                    }

                    itemService.updateItemPosition(e.getEntityId(), e.getLocationId(), positionType, e.getTimestamp(),
                            e.getProgress(), null);

                    // --- ADD TO NEW POSITION ---
                    if (positionType == PositionType.CONVEYOR) {
                        liveConveyorRepository.addItemToConveyor(e.getLocationId(), e.getEntityId(), e.getTimestamp());
                        handleItemEntryToConveyor(e.getEntityId(), e.getLocationId(), e.getTimestamp(), e.getProgress(),
                                previousPosId);
                    } else {
                        // Only add if it's a buffer location
                        if (location != null && location.getType() == LocationType.CHUTE) {
                            liveLocationRepository.addItemToLocation(e.getLocationId(), e.getEntityId());
                        }
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
                    cancelScheduledEvent(e.getEntityId());
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
                    cancelScheduledEvent(e.getEntityId());
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
                            publishEvent(new PathTraversedEvent(e.getEntityId(), lastState.getPositionId(),
                                    lastState.getType(), lastState.getPositionId(),
                                    lastState.getType(), List.of(lastState.getPositionId())));
                        }
                    }
                    cancelScheduledEvent(e.getEntityId());
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

                    itemService.fullUpdateItem(item);

                    if (shouldBroadcast) {
                        webSocketService.broadcastItemUpdated(new UpdateModel(item.getId(),
                                Map.of("destination", e.getLocationId(), "path", calculatedPath)), e.getTimestamp());
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
                            recalculateConveyorAccumulation(e.getEntityId());
                            wakeUpPrecedingConveyors(conveyor.getSourceLocationId());
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
                        logger.debug("Item on conveyor:" + item);
                        liveItemRepository.deleteItem(item);
                        if (shouldBroadcast)
                            webSocketService.broadcastItemDeleted(item, e.getTimestamp());
                    }
                    // Delete the Redis key for the location itself
                    liveLocationRepository.deleteLocation(e.getEntityId());

                    if (manageLogic) {
                        wakeUpPrecedingConveyors(e.getEntityId());
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

    private void publishEvent(DomainEvent event) {
        try {
            amqpTemplate.convertAndSend(itemEventsRoutingKey, event);
        } catch (Exception e) {
            logger.error("Failed to publish event of type {}", event.getEventType(), e);
        }
    }

    private void checkpointItems(String edgeId, double oldSpeed, Instant timestamp) {
        String simId = DatabaseContextHolder.getSimulationId();
        var allItems = liveConveyorRepository.getItemsOrderedByDistance(edgeId); // Using ordered set now
        Instant nowInstant = timestamp;

        for (var itemId : allItems) {
            var itemData = liveItemRepository.getItemState(itemId);
            var lastUpdateTime = itemData.getEntryTime();
            Double storedDistance = itemData.getAccumulatedDistance();

            if (lastUpdateTime != null) {
                long timeElapsed = nowInstant.toEpochMilli() - lastUpdateTime.toEpochMilli();
                double distanceTraveledSinceLastUpdate = (timeElapsed / 1000.0) * oldSpeed;
                double totalDistance = storedDistance + distanceTraveledSinceLastUpdate;
                liveItemRepository.checkpointPhysics(itemId, nowInstant, totalDistance);
                handleItemEntryToConveyor(itemId, edgeId, nowInstant, (totalDistance / oldSpeed) * 100, null);
            }
        }
    }

    private void handleItemEntryToConveyor(String itemId, String conveyorId, Instant timestamp, Double progress,
            String previousPosId) {
        if (progress == null)
            progress = 0.0;
        Conveyor conveyor = topologyProvider.getConveyorById(conveyorId);
        if (conveyor == null)
            return;

        double speed = conveyor.getSpeed();
        double length = conveyor.getLength();
        Double minDistance = conveyor.getMinDistance();

        if (speed <= 0) {
            cancelScheduledEvent(itemId);
            Double currentTail = liveConveyorRepository.getTailPosition(conveyorId);
            double itemDistance = length * (progress / 100.0);

            if (currentTail == null || itemDistance < currentTail) {
                liveConveyorRepository.updateTailPosition(conveyorId, itemDistance);
            }
            return;
        }

        double remainingDistance = length * (1.0 - progress / 100.0);
        long travelTimeMillis = (long) ((remainingDistance / speed) * 1000);
        Instant arrivalAtEnd = timestamp.plusMillis(travelTimeMillis);

        String nextConveyorId = calculateNextConveyor(itemId, conveyor.getTargetLocationId(), conveyorId);

        if (nextConveyorId != null) {
            Conveyor nextConv = topologyProvider.getConveyorById(nextConveyorId);
            if (isNextSegmentBlocked(nextConv)) {
                double effectiveMinDist = (minDistance != null) ? minDistance : 0.0;
                Double nextTail = liveConveyorRepository.getTailPosition(nextConveyorId);
                if (nextTail == null)
                    nextTail = 0.0;
                double stopAt = length - (nextTail + effectiveMinDist);
                if (stopAt < 0)
                    stopAt = 0;
                double distanceToStop = stopAt - (length * (progress / 100.0));
                if (distanceToStop > 0) {
                    long timeToStop = (long) ((distanceToStop / speed) * 1000);
                    scheduleEvent(new ItemPositionChangedEvent(itemId, conveyorId, (stopAt / length) * 100,
                            timestamp.plusMillis(timeToStop), conveyorId));
                    liveConveyorRepository.updateTailPosition(conveyorId, stopAt);
                } else {
                    cancelScheduledEvent(itemId);
                    double actualPos = length * (progress / 100.0);
                    if (actualPos > stopAt) {
                        // Force position back to stopAt because it's jammed
                        itemService.updateItemPosition(itemId, conveyorId, PositionType.CONVEYOR, timestamp,
                                (stopAt / length) * 100, null);
                        liveConveyorRepository.updateTailPosition(conveyorId, stopAt);
                    } else {
                        liveConveyorRepository.updateTailPosition(conveyorId, actualPos);
                    }
                }
            } else {
                scheduleEvent(new ItemPositionChangedEvent(itemId, nextConveyorId, 0.0, arrivalAtEnd, conveyorId));
                liveConveyorRepository.updateTailPosition(conveyorId, length);
            }
        } else {
            var targetLocation = topologyProvider.getLocationById(conveyor.getTargetLocationId());
            if (targetLocation != null && targetLocation.getType() == LocationType.CHUTE) {
                Integer capacity = targetLocation.getCapacity();
                Long currentOccupancy = liveLocationRepository.getItemCount(targetLocation.getId());
                if (capacity != null && currentOccupancy >= capacity) {
                    double effectiveMinDist = (minDistance != null) ? minDistance : 0.0;
                    double stopAt = length - effectiveMinDist;
                    long timeToStop = (long) (((stopAt - length * (progress / 100.0)) / speed) * 1000);
                    if (timeToStop > 0)
                        scheduleEvent(new ItemPositionChangedEvent(itemId, conveyorId, (stopAt / length) * 100,
                                timestamp.plusMillis(timeToStop), conveyorId));
                    liveConveyorRepository.updateTailPosition(conveyorId, stopAt);
                } else {
                    scheduleEvent(new ItemPositionChangedEvent(itemId, targetLocation.getId(), 100.0, arrivalAtEnd,
                            conveyorId));
                    // The occupancy is now handled by the ItemPositionChangedEvent itself. No
                    // manual increment needed.
                }
            }
        }
    }

    private boolean isNextSegmentBlocked(Conveyor nextConv) {
        if (nextConv == null)
            return true;
        if (!nextConv.isActive())
            return true;

        var targetLocation = topologyProvider.getLocationById(nextConv.getTargetLocationId());
        if (targetLocation != null && targetLocation.getType() == LocationType.CHUTE) {
            Integer capacity = targetLocation.getCapacity();
            Long currentOccupancy = liveLocationRepository.getItemCount(targetLocation.getId()); // Using accurate count
            return (capacity != null && currentOccupancy >= capacity);
        }
        return false;
    }

    private void wakeUpPrecedingConveyors(String locationId) {
        if (!manageLogic)
            return;
        topologyProvider.getAllConveyors().stream()
                .filter(c -> c.getTargetLocationId().equals(locationId))
                .forEach(c -> recalculateConveyorAccumulation(c.getId()));
    }

    private void recalculateConveyorAccumulation(String conveyorId) {
        if (!manageLogic)
            return;
        Set<String> items = liveConveyorRepository.getItemsOrderedByDistance(conveyorId); // Using ordered set
        Instant now = timeService.now();
        Conveyor conveyor = topologyProvider.getConveyorById(conveyorId);
        if (conveyor == null)
            return;
        for (String itemId : items) {
            var state = liveItemRepository.getItemState(itemId);
            if (state == null)
                continue;
            handleItemEntryToConveyor(itemId, conveyorId, now,
                    (state.getAccumulatedDistance() / conveyor.getLength()) * 100, null);
        }
    }

    private void scheduleEvent(DomainEvent event) {
        String simId = DatabaseContextHolder.getSimulationId();
        if (simId != null)
            simulationService.addInternalEvent(event);
        else
            liveSystemScheduler.scheduleInternalEvent(event);
    }

    private void cancelScheduledEvent(String itemId) {
        String simId = DatabaseContextHolder.getSimulationId();
        if (simId != null)
            simulationService.cancelInternalEvent(itemId);
        else
            liveSystemScheduler.cancelInternalEvent(itemId);
    }

    private String calculateNextConveyor(String itemId, String currentLocationId, String currentConveyorId) {
        var item = itemService.getItemById(itemId);
        List<Conveyor> outgoing = topologyProvider.getOutgoingConveyors(currentLocationId).stream()
                .filter(Conveyor::isActive).toList();
        if (outgoing.isEmpty())
            return null;

        String targetConveyorId = null;
        if (item.getPath() != null && !item.getPath().isEmpty()) {
            int currentIndex = item.getPath().indexOf(currentLocationId);
            if (currentIndex >= 0 && currentIndex < item.getPath().size() - 1) {
                String nextVertexId = item.getPath().get(currentIndex + 1);
                targetConveyorId = outgoing.stream().filter(c -> c.getTargetLocationId().equals(nextVertexId))
                        .map(Conveyor::getId).findFirst().orElse(null);
            }
        }

        if (targetConveyorId != null) {
            Conveyor target = topologyProvider.getConveyorById(targetConveyorId);
            if (target != null && isNextSegmentBlocked(target)) {
                // If the target conveyor is blocked (leads to full chute), try to recirculate
                // on main path
                return outgoing.stream().filter(Conveyor::isMainPath).map(Conveyor::getId).findFirst()
                        .orElse(targetConveyorId);
            }
            return targetConveyorId;
        }
        return outgoing.stream().filter(Conveyor::isMainPath).map(Conveyor::getId).findFirst()
                .orElse(outgoing.get(0).getId());
    }
}