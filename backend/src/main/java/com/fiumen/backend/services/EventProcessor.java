package com.fiumen.backend.services;

import com.fiumen.backend.domain.Conveyor;
import com.fiumen.backend.models.UpdateModel;
import com.fiumen.backend.models.input.ItemInput;
import com.fiumen.backend.models.input.LocationInput;
import com.fiumen.backend.models.response.ConveyorResponse;
import com.fiumen.backend.repositories.LiveItemRepository;
import com.orientechnologies.orient.core.exception.OConcurrentModificationException;

import fiumen.context.UserContextHolder;
import fiumen.events.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;
import com.fiumen.backend.context.DatabaseContextHolder;

@Service
public class EventProcessor {
    private static final Logger logger = LoggerFactory.getLogger(EventProcessor.class);

    private final ClickHouseService clickHouseService;
    private final ItemService itemService;
    private final LocationService locationService;
    private final ConveyorService conveyorService;
    private final WebSocketService webSocketService;
    private final PathfindingService pathfindingService;
    private final LiveItemRepository liveItemRepository;

    public EventProcessor(
            ClickHouseService clickHouseService,
            ItemService itemService,
            LocationService locationService,
            ConveyorService conveyorService,
            WebSocketService webSocketService,
            PathfindingService pathfindingService,
            LiveItemRepository liveItemRepository) {
        this.clickHouseService = clickHouseService;
        this.itemService = itemService;
        this.locationService = locationService;
        this.conveyorService = conveyorService;
        this.webSocketService = webSocketService;
        this.pathfindingService = pathfindingService;
        this.liveItemRepository = liveItemRepository;
    }

    public CompletableFuture<Map<String, Object>> process(DomainEvent event, boolean shouldBroadcast) {
        String capturedSimulationId = DatabaseContextHolder.getSimulationId();
        String capturedSenderId = UserContextHolder.getSenderId();
        return CompletableFuture.supplyAsync(() -> {

            // We inject the values into this new thread's ThreadLocal
            if (capturedSimulationId != null) {
                DatabaseContextHolder.enterSimulationContext(capturedSimulationId);
            }
            if (capturedSenderId != null) {
                UserContextHolder.setSenderId(capturedSenderId);
            }
            Map<String, Object> resultMap = new HashMap<>();
            try {
                clickHouseService.saveEvent(event);
                resultMap = processEvent(event, shouldBroadcast);
                logger.info("Successfully processed event: {}", event.getEventType());
                return resultMap;
            } catch (Exception e) {
                logger.error("Error processing event: {}", event.getEventType(), e);
                throw new CompletionException(e);
            } finally {
                DatabaseContextHolder.clearSimulation();
                UserContextHolder.clear();
            }
        });
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

    private Map<String, Object> processEvent(DomainEvent event, boolean shouldBroadcast) {
        UserContextHolder.setSenderId(event.getSenderId());
        return this.<Map<String, Object>>executeWithRetry(() -> {
            return switch (event) {
                // --- ITEM EVENTS ---

                case ItemCreatedEvent e -> {
                    var item = new ItemInput(e);
                    itemService.createItem(item);

                    if (shouldBroadcast) {
                        webSocketService.broadcastItemCreated(item, e.getTimestamp());
                    }
                    yield Map.of("status", "CREATED", "itemId", e.getEntityId());
                }

                case ItemPositionChangedEvent e -> {
                    var positionType = getPositionType(e.getLocationId());
                    itemService.updateItemPosition(e.getEntityId(), e.getLocationId(), e.getTimestamp(), positionType,
                            e.getProgress());

                    if (shouldBroadcast) {
                        webSocketService.broadcastPositionUpdate(e.getEntityId(), e.getLocationId(), e.getTimestamp(),
                                positionType,
                                e.getProgress());
                    }
                    yield Map.of("status", "PROCESSED_SUCCESSFULLY");
                }

                case ItemPositionDeletedEvent e -> {
                    itemService.updateItemPosition(e.getEntityId(), null, null, Instant.now(), null);
                    if (shouldBroadcast) {
                        webSocketService.broadcastPositionLost(e.getEntityId(), e.getTimestamp());
                    }
                    yield Map.of("status", "PROCESSED_SUCCESSFULLY");
                }

                case ItemRenamedEvent e -> {
                    var item = itemService.getItemById(e.getEntityId());
                    item.setName(e.getNewName());
                    itemService.updateItem(new UpdateModel(item.getId(), Map.of("name", e.getNewName())));

                    if (shouldBroadcast) {
                        webSocketService.broadcastItemUpdated(
                                new UpdateModel(item.getId(), Map.of("name", e.getNewName())), e.getTimestamp());
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
                    itemService.updateItem(new UpdateModel(item.getId(), Map.of("active", false)));
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
                    var updateModel = new UpdateModel(item.getId(), Map.of("properties", item.getProperties()));
                    itemService.updateItem(updateModel);
                    if (shouldBroadcast) {
                        webSocketService.broadcastItemUpdated(updateModel, e.getTimestamp());
                    }
                    yield Map.of("status", "PROCESSED_SUCCESSFULLY");
                }

                case ItemDeletedEvent e -> {
                    itemService.deleteItem(e.getEntityId());
                    if (shouldBroadcast) {
                        webSocketService.broadcastItemDeleted(e.getEntityId(), e.getTimestamp());
                    }
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
                    var updateModel = new UpdateModel(location.getId(), Map.of("properties", location.getProperties()));
                    locationService.updateLocation(updateModel);
                    if (shouldBroadcast) {
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
                            e.getIsMainPath(),
                            e.getIsActive());
                    if (shouldBroadcast) {
                        webSocketService.broadcastConnectionCreated(new ConveyorResponse(e.getConnectionId(),
                                e.getSourceId(), e.getTargetId(), e.getName(), e.getLength(), e.getSpeed(), e.getType(),
                                e.getIsActive(), e.getIsMainPath(), e.getCapacity()), e.getTimestamp());
                    }
                    yield Map.of("status", "PROCESSED_SUCCESSFULLY");
                }

                case ConnectionSpeedChangedEvent e -> {
                    var conveyor = conveyorService.getConveyorById(e.getEntityId());
                    conveyorService.updateConveyor(conveyor);
                    checkpointItems(e.getEntityId(), conveyor.getSpeed());
                    conveyor.setSpeed(e.getSpeed());
                    if (shouldBroadcast) {
                        webSocketService.broadcastConnectionUpdated(
                                new UpdateModel(conveyor.getId(), Map.of("speed", e.getSpeed())), e.getTimestamp());
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

                case LocationAddToMainPath e -> {
                    var conveyor = conveyorService.getConveyorById(e.getEntityId());

                    if (shouldBroadcast) {
                        webSocketService.broadcastConnectionUpdated(
                                new UpdateModel(conveyor.getId(), Map.of("isMainPath", true)), e.getTimestamp());
                    }
                    yield Map.of("status", "PROCESSED_SUCCESSFULLY");
                }

                case ConnectionRemoveFromMainPath e -> {
                    var conveyor = conveyorService.getConveyorById(e.getEntityId());

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

                default -> {
                    logger.warn("Unknown event type: {}", event.getClass().getSimpleName());
                    yield Map.of("status", "UNKNOWN_EVENT");
                }
            };
        });
    }

    /**
     * Checkpoints items on a conveyor when speed changes.
     * We save the distance traveled so far and reset the timer to 'now'.
     */
    private void checkpointItems(String edgeId, double oldSpeed) {
        List<Map<String, Object>> allItems = liveItemRepository.getAllActiveItems();
        long now = System.currentTimeMillis();
        Instant nowInstant = Instant.ofEpochMilli(now);

        for (Map<String, Object> itemData : allItems) {
            String currentEdgeId = (String) itemData.get("edgeId");

            if (edgeId.equals(currentEdgeId)) {
                String itemId = (String) itemData.get("id");
                Long lastUpdateTime = (Long) itemData.get("entryTimestamp");
                Double storedDistance = (Double) itemData.get("accumulatedDistance");
                if (storedDistance == null)
                    storedDistance = 0.0;

                if (lastUpdateTime != null) {
                    long timeElapsed = now - lastUpdateTime;
                    double distanceTraveledSinceLastUpdate = (timeElapsed / 1000.0) * oldSpeed;

                    double totalDistance = storedDistance + distanceTraveledSinceLastUpdate;

                    liveItemRepository.checkpointPhysics(itemId, nowInstant, totalDistance);
                }
            }
        }
    }
}