package com.flunav.backend.controllers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.flunav.backend.config.BlockInDemo;
import com.flunav.backend.controllers.LocationController.PropertyUpdateRequest;
import com.flunav.backend.domain.Item;
import com.flunav.backend.models.input.ItemInput;
import com.flunav.backend.models.input.PathUpdateRequest;
import com.flunav.backend.services.ItemService;
import com.flunav.backend.utils.ControllerHelper;

import flunav.events.DomainEvent;
import flunav.events.ItemCreatedEvent;
import flunav.events.ItemDeletedEvent;
import flunav.events.ItemPathChangedEvent;
import flunav.events.ItemPriorityUpdatedEvent;
import flunav.events.ItemPropertiesUpdatedEvent;
import flunav.events.ItemRenamedEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/items")
public class ItemController {
    private static final Logger logger = LoggerFactory.getLogger(ItemController.class);

    private final ItemService itemService;
    private final ControllerHelper eventProcessorHelper;
    private final com.flunav.backend.services.TimeService timeService;

    @Autowired
    public ItemController(ItemService itemService, ControllerHelper eventProcessorHelper, com.flunav.backend.services.TimeService timeService) {
        this.itemService = itemService;
        this.eventProcessorHelper = eventProcessorHelper;
        this.timeService = timeService;
    }

    @GetMapping
    public ResponseEntity<List<Item>> getAllItems() {
        List<Item> items = itemService.getAllItems();
        return ResponseEntity.ok(items);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Item> getItemById(@PathVariable String id) {
        Item item = itemService.getItemById(id);
        return item != null ? ResponseEntity.ok(item) : ResponseEntity.notFound().build();
    }

    @BlockInDemo
    @PostMapping()
    public CompletableFuture<ResponseEntity<Map<String, Object>>> createItem(@RequestBody ItemInput item) {
        try {
            validatePriority(item.getPriority());
            validateProperties(item.getProperties());
        } catch (IllegalArgumentException e) {
            logger.warn("Item creation validation failed for {}: {}", item.getId(), e.getMessage());
            return CompletableFuture.completedFuture(ResponseEntity.badRequest().build());
        }

        var event = new ItemCreatedEvent(
                item.getId(),
                item.getName(),
                item.getSpeed(),
                item.getPriority(),
                item.getActive(),
                item.getLocationId(),
                item.getPositionType(),
                item.getProgress(),
                item.getDestinations(),
                item.getProperties(),
                item.getTimestamp() != null ? item.getTimestamp() : timeService.now());
        return eventProcessorHelper.processAndLogEvent(event)
                .thenApply(updatedItemProperties -> {

                    java.net.URI location = java.net.URI.create("/api/items/" + event.getEntityId());
                    return ResponseEntity.created(location).body(updatedItemProperties);
                });
    }

    @BlockInDemo
    @PutMapping("/{id}")
    public CompletableFuture<ResponseEntity<Void>> updateItem(
            @PathVariable String id,
            @RequestBody Map<String, Object> updates) {

        List<DomainEvent> events = new ArrayList<>();

        try {
            if (updates.containsKey("name")) {
                Object value = updates.get("name");
                if (!(value instanceof String)) {
                    logger.warn("Validation failed for item {}: 'name' must be a string, but was {}", id,
                            value.getClass().getSimpleName());
                    return CompletableFuture.completedFuture(ResponseEntity.badRequest().build());
                }
                events.add(new ItemRenamedEvent(id, (String) value));
            }

            if (updates.containsKey("properties")) {
                Object value = updates.get("properties");
                if (value instanceof Map) {

                    @SuppressWarnings("unchecked")
                    Map<String, Object> props = (Map<String, Object>) value;
                    validateProperties(props);
                    events.add(new ItemPropertiesUpdatedEvent(id, props));
                } else {
                    logger.warn("Invalid type for 'properties' on item {}", id);
                    return CompletableFuture.completedFuture(ResponseEntity.badRequest().build());
                }
            }

            if (updates.containsKey("priority")) {
                Double priority = validatePriority(updates.get("priority"));
                events.add(new ItemPriorityUpdatedEvent(id, priority));
            }

            if (updates.containsKey("path")) {
                events.add(new ItemPathChangedEvent(id, validatePathValue(updates.get("path"))));
            }

        } catch (Exception e) {
            logger.warn("Item update validation failed for {}: {}", id, e.getMessage());
            return CompletableFuture.completedFuture(ResponseEntity.badRequest().build());
        }

        if (events.isEmpty()) {
            logger.info("Update request for location {} contained no updatable fields.", id);
            return CompletableFuture.completedFuture(ResponseEntity.ok().build());
        }

        List<CompletableFuture<Map<String, Object>>> futureList = events.stream()
                .map(event -> eventProcessorHelper.processAndLogEvent(event))
                .collect(Collectors.toList());

        CompletableFuture<?>[] futures = futureList.toArray(new CompletableFuture[0]);

        return CompletableFuture.allOf(futures)
                .thenApply(v -> {
                    logger.info("Successfully processed all {} events for item {}", events.size(), id);
                    return ResponseEntity.ok().<Void>build();
                })
                .exceptionally(ex -> {
                    logger.error("Error processing event batch for item {}. At least one event failed.", id,
                            ex.getCause());
                    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).<Void>build();
                });
    }

    @BlockInDemo
    @PutMapping("/{id}/path")
    public CompletableFuture<ResponseEntity<Void>> updateItemPath(
            @PathVariable String id,
            @RequestBody PathUpdateRequest request) {
        final List<String> path;
        try {
            path = validatePathValue(request.path());
        } catch (Exception e) {
            logger.warn("Item path validation failed for {}: {}", id, e.getMessage());
            return CompletableFuture.completedFuture(ResponseEntity.badRequest().build());
        }

        return eventProcessorHelper.processAndLogEvent(new ItemPathChangedEvent(id, path))
                .thenApply(result -> ResponseEntity.ok().<Void>build())
                .exceptionally(ex -> {
                    logger.error("Error updating path for item {}", id, ex.getCause());
                    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).<Void>build();
                });
    }

    private List<String> validatePathValue(Object value) {
        if (!(value instanceof List<?> rawPath)
                || rawPath.stream().anyMatch(entry -> !(entry instanceof String))) {
            throw new IllegalArgumentException("Path must be a list of strings.");
        }
        return itemService.validatePath(rawPath.stream().map(String.class::cast).toList());
    }

    private Double validatePriority(Object value) {
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("priority must be a number");
        }
        double priority = number.doubleValue();
        if (!Double.isFinite(priority) || priority < 0.0 || priority > 1.0) {
            throw new IllegalArgumentException("priority must be finite and between 0.0 and 1.0");
        }
        return priority;
    }

    private void validateProperties(Map<String, Object> properties) {
        if (properties != null
                && properties.keySet().stream().anyMatch(key -> key != null && key.equalsIgnoreCase("priority"))) {
            throw new IllegalArgumentException("priority is a top-level item field");
        }
    }

    @BlockInDemo
    @DeleteMapping("/{id}")
    public CompletableFuture<ResponseEntity<Map<String, Object>>> deleteItem(@PathVariable String id) {
        var event = new ItemDeletedEvent(id);
        return eventProcessorHelper.processAndLogEvent(event)
                .thenApply(result -> {

                    return ResponseEntity.noContent().build();
                });
    }
}
