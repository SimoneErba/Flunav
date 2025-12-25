package com.flonav.backend.controllers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.flonav.backend.domain.Conveyor;
import com.flonav.backend.services.ConveyorService;
import com.flonav.backend.utils.ControllerHelper;

import flonav.events.*;
import flonav.types.ConveyorType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/conveyors")
@Tag(name = "Conveyors", description = "APIs for managing conveyor connections between locations")
public class ConnectionController {

    private static final Logger logger = LoggerFactory.getLogger(ConnectionController.class);

    private final ConveyorService conveyorService;
    private final ControllerHelper eventProcessorHelper;

    // --- Input Records ---

    /**
     * Input record matching the fields required by ConnectionCreatedEvent.
     * connectionId is optional; if null, a UUID will be generated.
     */
    public record CreateConveyorInput(
            String connectionId,
            String sourceId,
            String targetId,
            String name,
            Double length,
            Double speed,
            Long timeToTraverseMs,
            Boolean isMainPath,
            Boolean isActive,
            ConveyorType type,
            Integer capacity) {
    }

    public record SpeedUpdateRequest(Double speed) {
    }

    public record LengthUpdateRequest(Double length) {
    }

    public record IsMainPathUpdateRequest(Boolean isMainPath) {
    }

    @Autowired
    public ConnectionController(ConveyorService conveyorService, ControllerHelper eventProcessorHelper) {
        this.conveyorService = conveyorService;
        this.eventProcessorHelper = eventProcessorHelper;
    }

    @GetMapping
    @Operation(summary = "Get all conveyors")
    public List<Conveyor> getAllConveyors() {
        return conveyorService.getAllConveyors();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a conveyor by ID")
    public ResponseEntity<Conveyor> getConveyorById(@PathVariable String id) {
        Conveyor conveyor = conveyorService.getConveyorById(id);
        return conveyor != null ? ResponseEntity.ok(conveyor) : ResponseEntity.notFound().build();
    }

    @PostMapping
    @Operation(summary = "Create a new conveyor connection")
    public CompletableFuture<ResponseEntity<Map<String, Object>>> createConveyor(
            @RequestBody CreateConveyorInput input) {
        // Ensure we have an ID for the entity
        String entityId = input.connectionId() != null && !input.connectionId().isBlank()
                ? input.connectionId()
                : UUID.randomUUID().toString();

        // The Event constructor handles nulls for optional fields (length, speed, etc.)
        // by applying defaults
        var event = new ConnectionCreatedEvent(
                entityId,
                input.sourceId(),
                input.targetId(),
                input.length(),
                input.speed(),
                input.timeToTraverseMs(),
                input.isMainPath(),
                input.name(),
                input.isActive(),
                input.type(),
                input.capacity());

        return eventProcessorHelper.processAndLogEvent(event)
                .thenApply(result -> {
                    URI location = URI.create("/api/conveyors/" + entityId);
                    return ResponseEntity.created(location).body(result);
                });
    }

    /**
     * Handles partial updates for a conveyor's properties from a single request.
     * Translates generic map updates into specific domain events (Speed, Length,
     * MainPath).
     */
    @PutMapping("/{id}")
    @Operation(summary = "Batch update conveyor properties")
    public CompletableFuture<ResponseEntity<Void>> updateConveyor(
            @PathVariable String id,
            @RequestBody Map<String, Object> updates) {

        List<DomainEvent> events = new ArrayList<>();

        try {
            // 1. Validate and create Speed Event
            if (updates.containsKey("speed")) {
                Object value = updates.get("speed");
                if (!(value instanceof Number)) {
                    logger.warn("Validation failed for conveyor {}: 'speed' must be a number.", id);
                    return CompletableFuture.completedFuture(ResponseEntity.badRequest().build());
                }
                events.add(new ConnectionSpeedChangedEvent(id, ((Number) value).doubleValue()));
            }

            // 2. Validate and create Length Event
            if (updates.containsKey("length")) {
                Object value = updates.get("length");
                if (!(value instanceof Number)) {
                    logger.warn("Validation failed for conveyor {}: 'length' must be a number.", id);
                    return CompletableFuture.completedFuture(ResponseEntity.badRequest().build());
                }
                events.add(new ConnectionLengthChangedEvent(id, ((Number) value).doubleValue()));
            }

            // 3. Validate and create Main Path Event
            if (updates.containsKey("isMainPath")) {
                Object value = updates.get("isMainPath");
                if (!(value instanceof Boolean)) {
                    logger.warn("Validation failed for conveyor {}: 'isMainPath' must be a boolean.", id);
                    return CompletableFuture.completedFuture(ResponseEntity.badRequest().build());
                }
                boolean isMain = (Boolean) value;
                events.add(isMain ? new LocationAddToMainPath(id) : new ConnectionRemoveFromMainPath(id));
            }

            // Note: If you have events for renaming or changing active status, add them
            // here similarly.

        } catch (Exception e) {
            logger.error("An unexpected error occurred during payload validation for conveyor {}", id, e);
            return CompletableFuture.completedFuture(ResponseEntity.badRequest().build());
        }

        if (events.isEmpty()) {
            logger.info("Update request for conveyor {} contained no updatable fields.", id);
            return CompletableFuture.completedFuture(ResponseEntity.ok().build());
        }

        // Process all events in parallel
        List<CompletableFuture<Map<String, Object>>> futureList = events.stream()
                .map(eventProcessorHelper::processAndLogEvent)
                .collect(Collectors.toList());

        CompletableFuture<?>[] futures = futureList.toArray(new CompletableFuture[0]);

        return CompletableFuture.allOf(futures)
                .thenApply(v -> {
                    logger.info("Successfully processed all {} events for conveyor {}", events.size(), id);
                    return ResponseEntity.ok().<Void>build();
                })
                .exceptionally(ex -> {
                    logger.error("Error processing event batch for conveyor {}.", id, ex.getCause());
                    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).<Void>build();
                });
    }

    @PutMapping("/{id}/speed")
    @Operation(summary = "Update conveyor speed")
    public CompletableFuture<ResponseEntity<Map<String, Object>>> updateConveyorSpeed(
            @PathVariable String id, @RequestBody SpeedUpdateRequest request) {
        var event = new ConnectionSpeedChangedEvent(id, request.speed());
        return eventProcessorHelper.processAndLogEvent(event)
                .thenApply(ResponseEntity::ok);
    }

    @PutMapping("/{id}/length")
    @Operation(summary = "Update conveyor length")
    public CompletableFuture<ResponseEntity<Map<String, Object>>> updateConveyorLength(
            @PathVariable String id, @RequestBody LengthUpdateRequest request) {
        var event = new ConnectionLengthChangedEvent(id, request.length());
        return eventProcessorHelper.processAndLogEvent(event)
                .thenApply(ResponseEntity::ok);
    }

    @PutMapping("/{id}/isMainPath")
    @Operation(summary = "Update if conveyor is part of the main path")
    public CompletableFuture<ResponseEntity<Map<String, Object>>> updateConveyorIsMainPath(
            @PathVariable String id, @RequestBody IsMainPathUpdateRequest request) {
        var event = request.isMainPath()
                ? new LocationAddToMainPath(id)
                : new ConnectionRemoveFromMainPath(id);
        return eventProcessorHelper.processAndLogEvent(event)
                .thenApply(ResponseEntity::ok);
    }

    @DeleteMapping
    @Operation(summary = "Delete a conveyor connection by Source and Target IDs")
    public CompletableFuture<ResponseEntity<Map<String, Object>>> deleteConveyor(
            @Parameter(description = "ID of the source location") @RequestParam String sourceId,
            @Parameter(description = "ID of the target location") @RequestParam String targetId) {

        // The EventProcessor specifically requires source and target IDs for deletion
        // logic
        var event = new ConnectionDeletedEvent(sourceId, targetId);

        return eventProcessorHelper.processAndLogEvent(event)
                .thenApply(result -> ResponseEntity.noContent().build());
    }
}