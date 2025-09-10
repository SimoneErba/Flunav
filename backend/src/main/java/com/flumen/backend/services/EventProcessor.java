package com.flumen.backend.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

import flumen.events.ConnectionDeletedEvent;
import flumen.events.DomainEvent;
import flumen.events.ItemActivatedEvent;
import flumen.events.ItemCreatedEvent;
import flumen.events.ItemDeactivatedEvent;
import flumen.events.ItemDeletedEvent;
import flumen.events.ItemPositionChangedEvent;
import flumen.events.ItemPositionDeletedEvent;
import flumen.events.ItemPropertiesUpdatedEvent;
import flumen.events.ItemSpeedChangedEvent;
import flumen.events.LocationConnectionCreatedEvent;
import flumen.events.LocationCreatedEvent;
import flumen.events.LocationDeletedEvent;
import flumen.events.LocationPropertiesUpdatedEvent;
import flumen.events.PositionChangedEvent;
import flumen.events.PositionCreatedEvent;

import com.flumen.backend.models.UpdateModel;
import com.flumen.backend.models.input.ItemInput;
import com.flumen.backend.models.input.LocationInput;
import com.orientechnologies.orient.core.exception.OConcurrentModificationException;

import java.time.Instant;
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

    private final EventStore eventStore;
    private final OrientDBService orientDBService;
    private final ClickHouseService clickHouseService;
    private final ItemService itemService;
    private final LocationService locationService;
    private final ConnectedToService connectionService;
    private final WebSocketService webSocketService;
    private final GraphService graphService;

    private static final ObjectMapper objectMapper = new ObjectMapper();

    public EventProcessor(
            EventStore eventStore, 
            OrientDBService orientDBService,
            ClickHouseService clickHouseService,
            ItemService itemService,
            LocationService locationService,
            ConnectedToService connectionService,
            GraphService graphService,
            WebSocketService webSocketService) {
        this.eventStore = eventStore;
        this.orientDBService = orientDBService;
        this.clickHouseService = clickHouseService;
        this.itemService = itemService;
        this.locationService = locationService;
        this.connectionService = connectionService;
        this.webSocketService = webSocketService;
        this.graphService = graphService;
    }

    public CompletableFuture<Map<String, Object>> process(DomainEvent event, boolean shouldBroadcast) {
        // Use supplyAsync to return our new Map
        return CompletableFuture.supplyAsync(() -> {
            // This map is like a Python dictionary. We'll add our results to it.
            Map<String, Object> resultMap = new HashMap<>();
            try {
                // 1. Store the event in OrientDB
                eventStore.saveEvents(List.of(event));
                
                // 2. Store the event in ClickHouse
                clickHouseService.saveEvent(event);
                
                // 3. Update the read model using existing services
                resultMap = processEvent(event);
                
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

    while (true) { // Loop indefinitely until success or permanent failure
        try {
            return operation.get();
        } catch (OConcurrentModificationException e) {
            attempt++;
            if (attempt >= MAX_RETRIES) {
                logger.error("Operation failed after {} retries due to persistent concurrent modification.", MAX_RETRIES, e);
                throw e; // Give up and re-throw the exception.
            }

            logger.warn("Concurrent modification detected. Retrying attempt {}/{}.", attempt, MAX_RETRIES);
            
            try {
                Thread.sleep(50 + new Random().nextInt(50)); // Wait before next attempt
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

   private Map<String, Object> processEvent(DomainEvent event, boolean shouldBroadcast) {
    return this.<Map<String, Object>>executeWithRetry(() -> {
        return switch (event) {
            case ItemCreatedEvent e -> {
                var item = new ItemInput(e);
                itemService.createItem(item);

                yield Map.of(
                    "status", "CREATED",
                    "itemId", e.getEntityId(),
                    "message", "Item was created successfully."
                );
            }

            case ItemPositionChangedEvent e -> {
                var item = itemService.getItemById(e.getEntityId());
                var location = locationService.getLocationById(e.getLocationId());
                item.updatePosition(location);
                itemService.fullUpdateItem(item);
                if (shouldBroadcast){
                    webSocketService.broadcastPositionUpdate(item.getId(), location.getId());
                }
                yield Map.of("status", "PROCESSED_SUCCESSFULLY");
            }

            case ItemSpeedChangedEvent e -> {
                var item = itemService.getItemById(e.getEntityId());
                item.updateSpeed(e);
                itemService.fullUpdateItem(item);
                yield Map.of("status", "PROCESSED_SUCCESSFULLY");
            }

            case ItemDeactivatedEvent e -> { // Corrected type
                var item = itemService.getItemById(e.getEntityId());
                item.stop();
                Map<String, Object> updateData = new HashMap<>();
                updateData.put("active", item.isActive());
                itemService.updateItem(new UpdateModel(item.getId(), updateData));
                yield Map.of("status", "PROCESSED_SUCCESSFULLY");
            }

            case ItemActivatedEvent e -> {
                var item = itemService.getItemById(e.getEntityId());
                item.resume();
                Map<String, Object> updateData = new HashMap<>();
                updateData.put("active", item.isActive());
                itemService.updateItem(new UpdateModel(item.getId(), updateData));
                yield Map.of("status", "PROCESSED_SUCCESSFULLY");
            }

            case ItemPropertiesUpdatedEvent e -> {
                var item = itemService.getItemById(e.getEntityId());
                item.updateProperties(e);
                Map<String, Object> updateData = new HashMap<>();
                updateData.put("properties", item.getProperties());
                itemService.updateItem(new UpdateModel(item.getId(), updateData));
                yield Map.of("status", "PROCESSED_SUCCESSFULLY");
            }

            case ItemDeletedEvent e -> {
                // TODO: DDD. mark to be deleted, then delete
                itemService.deleteItem(e.getEntityId());
                yield Map.of("status", "PROCESSED_SUCCESSFULLY");
            }

            case LocationCreatedEvent e -> {
                var location = new LocationInput(e);
                locationService.createLocation(location);
                yield Map.of("status", "PROCESSED_SUCCESSFULLY");
            }

            case LocationPropertiesUpdatedEvent e -> {
                var location = locationService.getLocationById(e.getEntityId());
                location.updateProperties(e);
                Map<String, Object> updateData = new HashMap<>();
                updateData.put("properties", location.getProperties());
                locationService.updateLocation(new UpdateModel(location.getId(), updateData));
                yield Map.of("status", "PROCESSED_SUCCESSFULLY");
            }

            case LocationConnectionCreatedEvent e -> {
                var fromLocation = locationService.getLocationById(e.getEntityId());
                fromLocation.addConnectionTo(e.getLocation2Id());

                locationService.fullUpdateLocation(fromLocation);
                yield Map.of("status", "PROCESSED_SUCCESSFULLY");
            }

            case LocationDeletedEvent e -> {
                // TODO: delete items? or put them inside
                locationService.deleteLocation(e.getEntityId());
                yield Map.of("status", "PROCESSED_SUCCESSFULLY");
            }

            case ConnectionDeletedEvent e -> {
                connectionService.deleteConnection(e.getSourceLocationId(), e.getTargetLocationId());
                yield Map.of("status", "PROCESSED_SUCCESSFULLY");
            }

            case PositionCreatedEvent e -> {
                var item = itemService.getItemById(e.getItemId());
                var location = locationService.getLocationById(e.getLocationId());
                item.updatePosition(location);
                itemService.fullUpdateItem(item);
                if (shouldBroadcast){
                    webSocketService.broadcastPositionUpdate(item.getId(), location.getId());
                }
                yield Map.of("status", "PROCESSED_SUCCESSFULLY");
            }

            case PositionChangedEvent e -> {
                var item = itemService.getItemById(e.getItemId());
                var location = locationService.getLocationById(e.getLocationId());
                item.updatePosition(location);
                itemService.fullUpdateItem(item);
                if (shouldBroadcast){
                    webSocketService.broadcastPositionUpdate(item.getId(), location.getId());
                }
                yield Map.of("status", "PROCESSED_SUCCESSFULLY");
            }

            case ItemPositionDeletedEvent e -> {
                var item = itemService.getItemById(e.getEntityId());
                item.updatePosition(null);
                itemService.fullUpdateItem(item);
                if (shouldBroadcast){
                    webSocketService.broadcastPositionLost(item.getId());
                }
                yield Map.of("status", "PROCESSED_SUCCESSFULLY");
            }

            default -> {logger.warn("Unknown event type: {}", event.getClass().getSimpleName());
                yield Map.of("status", "UNKNOWN_EVENT");}
            };
        });
    }
} 