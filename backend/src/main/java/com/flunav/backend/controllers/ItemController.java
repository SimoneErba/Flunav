package com.flunav.backend.controllers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.flunav.backend.controllers.LocationController.PropertyUpdateRequest;
import com.flunav.backend.domain.Item;
import com.flunav.backend.models.input.ItemInput;
import com.flunav.backend.services.ItemService;
import com.flunav.backend.utils.ControllerHelper;

import flunav.events.DomainEvent;
import flunav.events.ItemCreatedEvent;
import flunav.events.ItemDeletedEvent;
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

    @Autowired
    public ItemController(ItemService itemService, ControllerHelper eventProcessorHelper) {
        this.itemService = itemService;
        this.eventProcessorHelper = eventProcessorHelper;
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

    @PostMapping()
    public CompletableFuture<ResponseEntity<Map<String, Object>>> createItem(@RequestBody ItemInput item) {

        var event = new ItemCreatedEvent(
                item.getId(),
                item.getName(),
                item.getSpeed(),
                item.getActive(),
                item.getLocationId(),
                item.getProgress(),
                item.getProperties());
        return eventProcessorHelper.processAndLogEvent(event)
                .thenApply(updatedItemProperties -> {

                    java.net.URI location = java.net.URI.create("/api/items/" + event.getEntityId());
                    return ResponseEntity.created(location).body(updatedItemProperties);
                });
    }

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

        } catch (Exception e) {
            logger.error("An unexpected error occurred during payload validation for location {}", id, e);
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

    @PutMapping()
    public CompletableFuture<ResponseEntity<Map<String, Object>>> updateItemProperties(@PathVariable String id,
            @RequestBody PropertyUpdateRequest model) {
        var event = new ItemPropertiesUpdatedEvent(
                id,
                model.properties());
        return eventProcessorHelper.processAndLogEvent(event)
                .thenApply(updatedItemProperties -> {

                    return ResponseEntity.ok(updatedItemProperties);
                });
    }

    @DeleteMapping("/{id}")
    public CompletableFuture<ResponseEntity<Map<String, Object>>> deleteItem(@PathVariable String id) {
        var event = new ItemDeletedEvent(id);
        return eventProcessorHelper.processAndLogEvent(event)
                .thenApply(result -> {

                    return ResponseEntity.noContent().build();
                });
    }
}
