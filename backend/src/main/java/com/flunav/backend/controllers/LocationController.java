package com.flunav.backend.controllers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.flunav.backend.config.BlockInDemo;
import com.flunav.backend.domain.Location;
import com.flunav.backend.models.input.LocationInput;
import com.flunav.backend.services.LocationService;
import com.flunav.backend.utils.ControllerHelper;

import flunav.events.*;
import flunav.types.LocationType;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/locations")
public class LocationController {
    private static final Logger logger = LoggerFactory.getLogger(LocationController.class);

    private final LocationService locationService;
    private final ControllerHelper eventProcessorHelper;

    public record PropertyUpdateRequest(Map<String, Object> properties) {
    }

    public record SpeedUpdateRequest(Double speed) {
    }

    public record LengthUpdateRequest(Double length) {
    }

    public record CoordinatesUpdateRequest(Double latitude, Double longitude) {
    }

    public record mainPathUpdateRequest(Boolean mainPath) {
    }

    public record CapacityUpdateRequest(Integer capacity) {
    }

    public record TypeUpdateRequest(LocationType type) {
    }

    @Autowired
    public LocationController(LocationService locationService, ControllerHelper eventProcessorHelper) {
        this.locationService = locationService;
        this.eventProcessorHelper = eventProcessorHelper;
    }

    @GetMapping
    public List<Location> getAllLocations() {
        return locationService.getAllLocations();
    }

    @GetMapping("/{id}")
    public Location getLocationById(@PathVariable String id) {
        return locationService.getLocationById(id);
    }

    @BlockInDemo
    @PostMapping
    public CompletableFuture<ResponseEntity<Map<String, Object>>> createLocation(@RequestBody LocationInput location) {
        var event = new LocationCreatedEvent(
                location.getId(),
                location.getName(),
                location.getActive(),
                location.getLatitude(),
                location.getLongitude(),
                location.getType(),
                location.getCapacity(),
                location.getProperties());

        return eventProcessorHelper.processAndLogEvent(event)
                .thenApply(result -> {
                    URI url = URI.create("/api/locations/" + event.getEntityId());
                    return ResponseEntity.created(url).body(result);
                });
    }

    /**
     * Handles partial updates for a location's properties from a single request.
     * This endpoint translates a generic map of updates into specific domain events
     * and processes them as a single atomic operation.
     *
     * @param id      The ID of the location to update.
     * @param updates A map containing the properties to update.
     * @return A CompletableFuture with a ResponseEntity indicating success or
     *         failure.
     */
    @BlockInDemo
    @PutMapping("/{id}")
    public CompletableFuture<ResponseEntity<Void>> updateLocation(
            @PathVariable String id,
            @RequestBody Map<String, Object> updates) {

        List<DomainEvent> events = new ArrayList<>();

        try {
            if (updates.containsKey("speed")) {
                Object value = updates.get("speed");
                if (!(value instanceof Number)) {
                    logger.warn("Validation failed for location {}: 'speed' must be a number, but was {}", id,
                            value.getClass().getSimpleName());
                    return CompletableFuture.completedFuture(ResponseEntity.badRequest().build());
                }
                events.add(new ConnectionSpeedChangedEvent(id, ((Number) value).doubleValue()));
            }

            if (updates.containsKey("length")) {
                Object value = updates.get("length");
                if (!(value instanceof Number)) {
                    logger.warn("Validation failed for location {}: 'length' must be a number, but was {}", id,
                            value.getClass().getSimpleName());
                    return CompletableFuture.completedFuture(ResponseEntity.badRequest().build());
                }
                events.add(new ConnectionLengthChangedEvent(id, ((Number) value).doubleValue()));
            }

            if (updates.containsKey("capacity")) {
                Object value = updates.get("capacity");
                if (!(value instanceof Number)) {
                    logger.warn("Validation failed for location {}: 'capacity' must be a number, but was {}", id,
                            value.getClass().getSimpleName());
                    return CompletableFuture.completedFuture(ResponseEntity.badRequest().build());
                }
                events.add(new LocationCapacityChangedEvent(id, ((Number) value).intValue()));
            }

            if (updates.containsKey("mainPath")) {
                Object value = updates.get("mainPath");
                if (!(value instanceof Boolean)) {
                    logger.warn("Validation failed for location {}: 'mainPath' must be a boolean, but was {}", id,
                            value.getClass().getSimpleName());
                    return CompletableFuture.completedFuture(ResponseEntity.badRequest().build());
                }
                events.add((Boolean) value ? new LocationAddToMainPath(id) : new ConnectionRemoveFromMainPath(id));
            }

            if (updates.containsKey("coordinates")) {
                Object coordsObj = updates.get("coordinates");
                if (!(coordsObj instanceof Map)) {
                    logger.warn("Validation failed for location {}: 'coordinates' must be a JSON object, but was {}",
                            id, coordsObj.getClass().getSimpleName());
                    return CompletableFuture.completedFuture(ResponseEntity.badRequest().build());
                }
                Map<?, ?> coords = (Map<?, ?>) coordsObj;
                Object latObj = coords.get("latitude");
                Object lonObj = coords.get("longitude");

                if (!(latObj instanceof Number) || !(lonObj instanceof Number)) {
                    logger.warn("Validation failed for location {}: 'latitude' and 'longitude' must both be numbers.",
                            id);
                    return CompletableFuture.completedFuture(ResponseEntity.badRequest().build());
                }
                events.add(new LocationCoordinatesChangedEvent(id, ((Number) latObj).doubleValue(),
                        ((Number) lonObj).doubleValue()));
            }

            if (updates.containsKey("properties")) {
                Object value = updates.get("properties");
                if (value instanceof Map) {

                    @SuppressWarnings("unchecked")
                    Map<String, Object> props = (Map<String, Object>) value;
                    events.add(new LocationPropertiesUpdatedEvent(id, props));
                } else {
                    logger.warn("Invalid type for 'properties' on item {}", id);
                    return CompletableFuture.completedFuture(ResponseEntity.badRequest().build());
                }
            }

            if (updates.containsKey("type")) {
                Object value = updates.get("type");
                LocationType type = null;
                if (value instanceof String) {
                    type = LocationType.fromString((String) value);
                } else if (value instanceof LocationType) {
                    type = (LocationType) value;
                }

                if (type != null) {
                    events.add(new LocationTypeChangedEvent(id, type));
                } else {
                    logger.warn("Validation failed for location {}: 'type' is invalid, but was {}", id,
                            value != null ? value.getClass().getSimpleName() : "null");
                    return CompletableFuture.completedFuture(ResponseEntity.badRequest().build());
                }
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
                    logger.info("Successfully processed all {} events for location {}", events.size(), id);
                    return ResponseEntity.ok().<Void>build();
                })
                .exceptionally(ex -> {
                    logger.error("Error processing event batch for location {}. At least one event failed.", id,
                            ex.getCause());
                    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).<Void>build();
                });
    }

    @BlockInDemo
    @PutMapping("/{id}/properties")
    public CompletableFuture<ResponseEntity<Map<String, Object>>> updateLocationProperties(
            @PathVariable String id, @RequestBody PropertyUpdateRequest request) {
        var event = new LocationPropertiesUpdatedEvent(id, request.properties());
        return eventProcessorHelper.processAndLogEvent(event)
                .thenApply(result -> ResponseEntity.ok(result));
    }

    @BlockInDemo
    @PutMapping("/{id}/speed")
    public CompletableFuture<ResponseEntity<Map<String, Object>>> updateLocationSpeed(
            @PathVariable String id, @RequestBody SpeedUpdateRequest request) {
        var event = new ConnectionSpeedChangedEvent(id, request.speed());
        return eventProcessorHelper.processAndLogEvent(event)
                .thenApply(result -> ResponseEntity.ok(result));
    }

    @BlockInDemo
    @PutMapping("/{id}/length")
    public CompletableFuture<ResponseEntity<Map<String, Object>>> updateLocationLength(
            @PathVariable String id, @RequestBody LengthUpdateRequest request) {
        var event = new ConnectionLengthChangedEvent(id, request.length());
        return eventProcessorHelper.processAndLogEvent(event)
                .thenApply(result -> ResponseEntity.ok(result));
    }

    @BlockInDemo
    @PutMapping("/{id}/coordinates")
    public CompletableFuture<ResponseEntity<Map<String, Object>>> updateLocationCoordinates(
            @PathVariable String id, @RequestBody CoordinatesUpdateRequest request) {
        var event = new LocationCoordinatesChangedEvent(id, request.latitude(), request.longitude());
        return eventProcessorHelper.processAndLogEvent(event)
                .thenApply(result -> ResponseEntity.ok(result));
    }

    @BlockInDemo
    @PutMapping("/{id}/mainPath")
    public CompletableFuture<ResponseEntity<Map<String, Object>>> updateLocationmainPath(
            @PathVariable String id, @RequestBody mainPathUpdateRequest request) {
        var event = request.mainPath() ? new LocationAddToMainPath(id)
                : new ConnectionRemoveFromMainPath(id);
        return eventProcessorHelper.processAndLogEvent(event)
                .thenApply(result -> ResponseEntity.ok(result));
    }

    @BlockInDemo
    @PutMapping("/{id}/capacity")
    public CompletableFuture<ResponseEntity<Map<String, Object>>> updateLocationCapacity(
            @PathVariable String id, @RequestBody CapacityUpdateRequest request) {
        var event = new LocationCapacityChangedEvent(id, request.capacity());
        return eventProcessorHelper.processAndLogEvent(event)
                .thenApply(result -> ResponseEntity.ok(result));
    }

    @BlockInDemo
    @PutMapping("/{id}/type")
    public CompletableFuture<ResponseEntity<Map<String, Object>>> updateLocationType(
            @PathVariable String id, @RequestBody TypeUpdateRequest request) {
        var event = new LocationTypeChangedEvent(id, request.type());
        return eventProcessorHelper.processAndLogEvent(event)
                .thenApply(result -> ResponseEntity.ok(result));
    }

    @BlockInDemo
    @DeleteMapping("/{id}")
    public CompletableFuture<ResponseEntity<Void>> deleteLocation(@PathVariable String id) {
        var event = new LocationDeletedEvent(id);
        return eventProcessorHelper.processAndLogEvent(event)
                .thenApply(result -> ResponseEntity.noContent().build());
    }
}