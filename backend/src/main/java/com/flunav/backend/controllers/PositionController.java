package com.flunav.backend.controllers;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.flunav.backend.config.BlockInDemo;
import com.flunav.backend.models.input.CreateConnection;
import com.flunav.backend.utils.ControllerHelper;

import flunav.events.ItemPositionChangedEvent;
import flunav.events.ItemPositionDeletedEvent;
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
    @Operation(summary = "Move an item to a new position")
    public CompletableFuture<ResponseEntity<Map<String, Object>>> changePosition(@RequestBody CreateConnection model) {
        var event = new ItemPositionChangedEvent(
                model.getItemId(),
                model.getLocationId(),
                model.getProgress());

        return eventProcessorHelper.processAndLogEvent(event)
                .thenApply(result -> {
                    return ResponseEntity.noContent().build();
                });
    }

    @BlockInDemo
    @DeleteMapping("/{itemId}")
    @Operation(summary = "Delete position connections for an item")
    public CompletableFuture<ResponseEntity<Map<String, Object>>> deleteConnections(
            @Parameter(description = "ID of the item") @PathVariable String itemId) {
        var event = new ItemPositionDeletedEvent(itemId);
        return eventProcessorHelper.processAndLogEvent(event)
                .thenApply(result -> {
                    return ResponseEntity.noContent().build();
                });
    }
}