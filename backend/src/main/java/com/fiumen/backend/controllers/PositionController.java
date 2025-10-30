package com.fiumen.backend.controllers;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.fiumen.backend.models.input.CreateConnection;
import com.fiumen.backend.services.PositionService;
import com.fiumen.backend.services.WebSocketService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/positions")
@Tag(name = "Positions", description = "APIs for managing item positions")
public class PositionController {

    private final PositionService positionService;
    private final WebSocketService webSocketService;

    @Autowired
    public PositionController(PositionService positionService, WebSocketService webSocketService) {
        this.positionService = positionService;
        this.webSocketService = webSocketService;
    }

    @PostMapping()
    @Operation(summary = "Create a new position connection")
    public ResponseEntity<String> createConnection(@RequestBody CreateConnection model) {
        try {
            positionService.createConnection(model.getItemId(), model.getLocationId());
            webSocketService.broadcastPositionUpdate(model.getItemId(), model.getLocationId());
            return ResponseEntity.ok("Connection created successfully.");
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(String.format("Error while createConnection: %s", e.getMessage()));
        }
    }

    @PutMapping()
    @Operation(summary = "Move an item to a new position")
    public ResponseEntity<String> moveConnection(@RequestBody CreateConnection model) {
        try {
            positionService.moveConnection(model.getItemId(), model.getLocationId());
            webSocketService.broadcastPositionUpdate(model.getItemId(), model.getLocationId());
            return ResponseEntity.ok("Connection moved successfully.");
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(String.format("Error while moveConnection: %s", e.getMessage()));
        }
    }

    @DeleteMapping("/{itemId}")
    @Operation(summary = "Delete position connections for an item")
    public ResponseEntity<String> deleteConnections(
            @Parameter(description = "ID of the item") 
            @PathVariable String itemId) {
        try {
            positionService.deleteConnections(itemId);
            webSocketService.broadcastPositionUpdate(itemId, null);
            return ResponseEntity.ok("Connections deleted successfully.");
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(String.format("Error while deleteConnections: %s", e.getMessage()));
        }
    }
}
