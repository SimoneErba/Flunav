package com.flumen.backend.controllers;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.flumen.backend.domain.Item;
import com.flumen.backend.models.UpdateModel;
import com.flumen.backend.models.input.ItemInput;
import com.flumen.backend.services.ItemService;
import com.flumen.backend.utils.ControllerHelper;

import flumen.events.ItemCreatedEvent;
import flumen.events.ItemDeletedEvent;
import flumen.events.ItemPropertiesUpdatedEvent;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/items")
public class ItemController {

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
            item.getProperties()
        );
        return eventProcessorHelper.processAndLogEvent(event)
        .thenApply(updatedItemProperties -> {

            java.net.URI location = java.net.URI.create("/api/items/" + event.getEntityId());
            return ResponseEntity.created(location).body(updatedItemProperties);
        });
    }

    @PutMapping()
    public CompletableFuture<ResponseEntity<Map<String, Object>>> updateItem(@RequestBody UpdateModel model) {
        var event = new ItemPropertiesUpdatedEvent(
            model.getId(),
            model.getProperties()
        );
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
