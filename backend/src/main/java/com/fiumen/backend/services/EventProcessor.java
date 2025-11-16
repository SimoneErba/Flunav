package com.fiumen.backend.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import fiumen.events.ConnectionDeletedEvent;
import fiumen.events.DomainEvent;
import fiumen.events.ItemActivatedEvent;
import fiumen.events.ItemCreatedEvent;
import fiumen.events.ItemDeactivatedEvent;
import fiumen.events.ItemDeletedEvent;
import fiumen.events.ItemDestinationEvent;
import fiumen.events.ItemPositionChangedEvent;
import fiumen.events.ItemPositionCreatedEvent;
import fiumen.events.ItemPositionDeletedEvent;
import fiumen.events.ItemPropertiesUpdatedEvent;
import fiumen.events.ItemRenamedEvent;
import fiumen.events.ItemSpeedChangedEvent;
import fiumen.events.LocationAddToMainPath;
import fiumen.events.LocationCapacityChangedEvent;
import fiumen.events.LocationConnectionCreatedEvent;
import fiumen.events.LocationCoordinatesChangedEvent;
import fiumen.events.LocationCreatedEvent;
import fiumen.events.LocationDeletedEvent;
import fiumen.events.LocationLengthChangedEvent;
import fiumen.events.LocationPropertiesUpdatedEvent;
import fiumen.events.LocationRemoveFromMainPath;
import fiumen.events.LocationSpeedChangedEvent;

import com.fiumen.backend.models.UpdateModel;
import com.fiumen.backend.models.input.ItemInput;
import com.fiumen.backend.models.input.LocationInput;
import com.orientechnologies.orient.core.exception.OConcurrentModificationException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

@Service
public class EventProcessor {
    private static final Logger logger = LoggerFactory.getLogger(EventProcessor.class);

    private final ClickHouseService clickHouseService;
    private final ItemService itemService;
    private final LocationService locationService;
    private final ConnectedToService connectionService;
    private final WebSocketService webSocketService;
    private final PathfindingService pathfindingService;

    public EventProcessor(
            ClickHouseService clickHouseService,
            ItemService itemService,
            LocationService locationService,
            ConnectedToService connectionService,
            WebSocketService webSocketService,
            PathfindingService pathfindingService) {
        this.clickHouseService = clickHouseService;
        this.itemService = itemService;
        this.locationService = locationService;
        this.connectionService = connectionService;
        this.webSocketService = webSocketService;
        this.pathfindingService = pathfindingService;
    }

    public CompletableFuture<Map<String, Object>> process(DomainEvent event, boolean shouldBroadcast) {
        return CompletableFuture.supplyAsync(() -> {
            Map<String, Object> resultMap = new HashMap<>();
            try {
                clickHouseService.saveEvent(event);

                resultMap = processEvent(event, shouldBroadcast);

                logger.info("Successfully processed event: {}",
                        event.getEventType());

                return resultMap;
            } catch (Exception e) {
                logger.error("Error processing event: {}",
                        event.getEventType(), e);
                throw new CompletionException(e);
            }
        });
    }

    /**
     * A generic helper that executes a database operation and retries it
     * automatically if a OConcurrentModificationException occurs.
     *
     * @param operation The block of code to execute.
     */
    private <T> T executeWithRetry(Supplier<T> operation) {
        final int MAX_RETRIES = 3;
        int attempt = 0;

        while (true) {
            try {
                return operation.get();
            } catch (OConcurrentModificationException e) {
                attempt++;
                if (attempt >= MAX_RETRIES) {
                    logger.error("Operation failed after {} retries due to persistent concurrent modification.",
                            MAX_RETRIES, e);
                    throw e;
                }

                logger.warn("Concurrent modification detected. Retrying attempt {}/{}.", attempt, MAX_RETRIES);

                try {
                    Thread.sleep(50 + new Random().nextInt(50));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Retry attempt was interrupted", ie);
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
        return this.<Map<String, Object>>executeWithRetry(() -> {
            return switch (event) {
                case ItemCreatedEvent e -> {
                    var item = new ItemInput(e);
                    itemService.createItem(item);

                    if (shouldBroadcast) {
                        webSocketService.broadcastItemCreated(item);
                    }
                    yield Map.of(
                            "status", "CREATED",
                            "itemId", e.getEntityId(),
                            "message", "Item was created successfully.");
                }

                case ItemPositionChangedEvent e -> {
                    var item = itemService.getItemById(e.getEntityId());
                    var location = locationService.getLocationById(e.getLocationId());
                    item.updatePosition(location, e.getTimestamp());
                    itemService.fullUpdateItem(item);
                    if (shouldBroadcast) {
                        webSocketService.broadcastPositionUpdate(item.getId(), location.getId());
                    }
                    yield Map.of("status", "PROCESSED_SUCCESSFULLY");
                }

                case ItemPositionDeletedEvent e -> {
                    var item = itemService.getItemById(e.getEntityId());
                    item.updatePosition(null, null);
                    itemService.fullUpdateItem(item);
                    if (shouldBroadcast) {
                        webSocketService.broadcastPositionLost(item.getId());
                    }
                    yield Map.of("status", "PROCESSED_SUCCESSFULLY");
                }

                case ItemPositionCreatedEvent e -> {
                    var item = itemService.getItemById(e.getEntityId());
                    var location = locationService.getLocationById(e.getLocationId());
                    item.updatePosition(location, e.getTimestamp());
                    itemService.fullUpdateItem(item);
                    if (shouldBroadcast) {
                        webSocketService.broadcastPositionUpdate(item.getId(), location.getId());
                    }
                    yield Map.of("status", "PROCESSED_SUCCESSFULLY");
                }

                case ItemRenamedEvent e -> {
                    var item = itemService.getItemById(e.getEntityId());
                    item.updateName(e.getNewName());
                    itemService.fullUpdateItem(item);
                    if (shouldBroadcast) {
                        webSocketService
                                .broadcastItemUpdated(new UpdateModel(item.getId(), Map.of("name", e.getNewName())));
                    }
                    yield Map.of("status", "PROCESSED_SUCCESSFULLY");
                }

                case ItemSpeedChangedEvent e -> {
                    var item = itemService.getItemById(e.getEntityId());
                    item.updateSpeed(e.getSpeed(), e.getTimestamp());
                    itemService.fullUpdateItem(item);
                    if (shouldBroadcast) {
                        webSocketService
                                .broadcastItemUpdated(new UpdateModel(item.getId(), Map.of("speed", item.getSpeed())));
                    }
                    yield Map.of("status", "PROCESSED_SUCCESSFULLY");
                }

                case LocationAddToMainPath e -> {
                    var location = locationService.getLocationById(e.getEntityId());
                    location.setIsMainPath(true);
                    var updateModel = new UpdateModel(location.getId(), Map.of("isMainPath", true));
                    locationService.updateLocation(updateModel);
                    if (shouldBroadcast) {
                        webSocketService.broadcastLocationPropertiesUpdated(updateModel);
                    }
                    yield Map.of("status", "PROCESSED_SUCCESSFULLY");
                }

                case LocationRemoveFromMainPath e -> {
                    var location = locationService.getLocationById(e.getEntityId());
                    location.setIsMainPath(false);
                    var updateModel = new UpdateModel(location.getId(), Map.of("isMainPath", false));
                    locationService.updateLocation(updateModel);
                    if (shouldBroadcast) {
                        webSocketService.broadcastLocationPropertiesUpdated(updateModel);
                    }
                    yield Map.of("status", "PROCESSED_SUCCESSFULLY");
                }

                case ItemDeactivatedEvent e -> {
                    var item = itemService.getItemById(e.getEntityId());
                    item.stop();
                    Map<String, Object> updateData = new HashMap<>();
                    updateData.put("active", item.isActive());
                    itemService.updateItem(new UpdateModel(item.getId(), updateData));
                    if (shouldBroadcast) {
                        webSocketService
                                .broadcastItemUpdated(new UpdateModel(item.getId(), Map.of("active", item.isActive())));
                    }
                    yield Map.of("status", "PROCESSED_SUCCESSFULLY");
                }

                case ItemActivatedEvent e -> {
                    var item = itemService.getItemById(e.getEntityId());
                    item.resume();
                    Map<String, Object> updateData = new HashMap<>();
                    updateData.put("active", item.isActive());
                    itemService.updateItem(new UpdateModel(item.getId(), updateData));
                    if (shouldBroadcast) {
                        webSocketService
                                .broadcastItemUpdated(new UpdateModel(item.getId(), Map.of("active", item.isActive())));
                    }
                    yield Map.of("status", "PROCESSED_SUCCESSFULLY");
                }

                case ItemPropertiesUpdatedEvent e -> {
                    var item = itemService.getItemById(e.getEntityId());
                    item.updateProperties(e);
                    Map<String, Object> updateData = new HashMap<>();
                    updateData.put("properties", item.getProperties());
                    var updateModel = new UpdateModel(item.getId(), updateData);
                    itemService.updateItem(updateModel);
                    if (shouldBroadcast) {
                        webSocketService.broadcastItemUpdated(updateModel);
                    }
                    yield Map.of("status", "PROCESSED_SUCCESSFULLY");
                }

                case ItemDeletedEvent e -> {
                    // TODO: DDD. mark to be deleted, then delete
                    itemService.deleteItem(e.getEntityId());
                    if (shouldBroadcast) {
                        webSocketService.broadcastItemDeleted(e.getEntityId());
                    }
                    yield Map.of("status", "PROCESSED_SUCCESSFULLY");
                }

                case ItemDestinationEvent e -> {
                    var item = itemService.getItemById(e.getEntityId());
                    item.setDestination(e.getLocationId());
                    List<String> calculatedPath = pathfindingService.calculateShortestPath(e.getEntityId(),
                            e.getLocationId());

                    item.setPath(calculatedPath);
                    Map<String, Object> updateData = new HashMap<>();
                    updateData.put("destination", e.getLocationId());
                    updateData.put("path", item.getPath());

                    var updateModel = new UpdateModel(item.getId(), updateData);
                    if (shouldBroadcast) {
                        webSocketService.broadcastItemUpdated(updateModel);
                    }
                    yield Map.of("status", "PROCESSED_SUCCESSFULLY");
                }

                case LocationCreatedEvent e -> {
                    var location = new LocationInput(e);
                    locationService.createLocation(location);
                    if (shouldBroadcast) {
                        webSocketService.broadcastLocationCreated(location);
                    }
                    yield Map.of("status", "PROCESSED_SUCCESSFULLY");
                }

                case LocationPropertiesUpdatedEvent e -> {
                    var location = locationService.getLocationById(e.getEntityId());
                    location.updateProperties(e);
                    Map<String, Object> updateData = new HashMap<>();
                    updateData.put("properties", location.getProperties());
                    locationService.updateLocation(new UpdateModel(location.getId(), updateData));
                    if (shouldBroadcast) {
                        webSocketService.broadcastLocationPropertiesUpdated(
                                new UpdateModel(location.getId(), Map.of("properties", location.getProperties())));
                    }
                    yield Map.of("status", "PROCESSED_SUCCESSFULLY");
                }

                case LocationSpeedChangedEvent e -> {
                    var location = locationService.getLocationById(e.getEntityId());
                    location.updateSpeed(e.getSpeed());

                    var updateModel = new UpdateModel(location.getId(), Map.of("speed", location.getSpeed()));
                    locationService.updateLocation(updateModel);

                    if (shouldBroadcast) {
                        webSocketService.broadcastLocationPropertiesUpdated(updateModel);
                    }

                    yield Map.of("status", "PROCESSED_SUCCESSFULLY");
                }

                case LocationLengthChangedEvent e -> {
                    var location = locationService.getLocationById(e.getEntityId());
                    location.updateLength(e.getLength());

                    var updateModel = new UpdateModel(location.getId(), Map.of("length", location.getSpeed()));
                    locationService.updateLocation(updateModel);

                    if (shouldBroadcast) {
                        webSocketService.broadcastLocationPropertiesUpdated(updateModel);
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
                        webSocketService.broadcastLocationPropertiesUpdated(updateModel);
                    }
                    yield Map.of("status", "PROCESSED_SUCCESSFULLY");
                }

                case LocationCapacityChangedEvent e -> {
                    var location = locationService.getLocationById(e.getEntityId());
                    location.updateCapacity(e.getCapacity());

                    var updateModel = new UpdateModel(location.getId(),
                            Map.of("capacity", location.getCapacity()));
                    locationService.updateLocation(updateModel);

                    if (shouldBroadcast) {
                        webSocketService.broadcastLocationPropertiesUpdated(updateModel);
                    }
                    yield Map.of("status", "PROCESSED_SUCCESSFULLY");
                }

                // Here we dont use DDD because it's an operation on multiple elements and
                // doesnt have particular logics
                case LocationConnectionCreatedEvent e -> {
                    connectionService.createConnection(e.getEntityId(), e.getLocation2Id());

                    if (shouldBroadcast) {
                        webSocketService.broadcastConnectionCreated(e.getEntityId(), e.getLocation2Id());
                    }
                    yield Map.of("status", "PROCESSED_SUCCESSFULLY");
                }

                case LocationDeletedEvent e -> {
                    // TODO: delete items? or put them inside
                    locationService.deleteLocation(e.getEntityId());
                    if (shouldBroadcast) {
                        webSocketService.broadcastLocationDeleted(e.getEntityId());
                    }
                    yield Map.of("status", "PROCESSED_SUCCESSFULLY");
                }

                case ConnectionDeletedEvent e -> {
                    connectionService.deleteConnection(e.getSourceLocationId(), e.getTargetLocationId());
                    if (shouldBroadcast) {
                        webSocketService.broadcastConnectionDeleted(e.getSourceLocationId(), e.getTargetLocationId());
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
}