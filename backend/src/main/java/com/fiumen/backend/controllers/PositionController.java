package com.fiumen.backend.controllers;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.fiumen.backend.models.input.CreateConnection;
import com.fiumen.backend.utils.ControllerHelper;

import fiumen.events.ItemPositionChangedEvent;
import fiumen.events.ItemPositionCreatedEvent;
import fiumen.events.ItemPositionDeletedEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/positions")
@Tag(name = "Positions", description = "APIs for managing item positions")
public class PositionController {

    private final ControllerHelper eventProcessorHelper;

    @Autowired
    public PositionController(ControllerHelper eventProcessorHelper) {
        this.eventProcessorHelper = eventProcessorHelper;
    }

    @PostMapping()
    @Operation(summary = "Create a new position connection")
    public CompletableFuture<ResponseEntity<Map<String, Object>>> createConnection(@RequestBody CreateConnection model) {
        var event = new ItemPositionCreatedEvent(model.getItemId(), model.getLocationId());
        return eventProcessorHelper.processAndLogEvent(event)
            .thenApply(result -> {
                return ResponseEntity.noContent().build();
            });
    }

    @PutMapping()
    @Operation(summary = "Move an item to a new position")
    public CompletableFuture<ResponseEntity<Map<String, Object>>> moveConnection(@RequestBody CreateConnection model) {
        var event = new ItemPositionChangedEvent(model.getItemId(), model.getLocationId());
        return eventProcessorHelper.processAndLogEvent(event)
            .thenApply(result -> {
                return ResponseEntity.noContent().build();
            });
    }

    @DeleteMapping("/{itemId}")
    @Operation(summary = "Delete position connections for an item")
    public CompletableFuture<ResponseEntity<Map<String, Object>>> deleteConnections(
            @Parameter(description = "ID of the item") 
            @PathVariable String itemId) {
        var event = new ItemPositionDeletedEvent(itemId);
        return eventProcessorHelper.processAndLogEvent(event)
            .thenApply(result -> {
                return ResponseEntity.noContent().build();
            });
    }
}
