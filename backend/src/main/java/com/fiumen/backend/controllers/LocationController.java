package com.fiumen.backend.controllers;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.fiumen.backend.domain.Location;
import com.fiumen.backend.models.UpdateModel;
import com.fiumen.backend.models.input.LocationInput;
import com.fiumen.backend.services.LocationService;
import com.fiumen.backend.utils.ControllerHelper;

import fiumen.events.LocationCreatedEvent;
import fiumen.events.LocationDeletedEvent;
import fiumen.events.LocationPropertiesUpdatedEvent;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/locations")
public class LocationController {

    private final LocationService locationService;
    private final ControllerHelper eventProcessorHelper;

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

    @PostMapping
    public CompletableFuture<ResponseEntity<Map<String, Object>>> createLocation(@RequestBody LocationInput location) {
        var event = new LocationCreatedEvent(
            location.getId(),
            location.getName(),
            location.getActive(),
            location.getLatitude(),
            location.getLongitude(),
            location.getLength(),
            location.getSpeed(),
            location.getType(),
            location.getCapactiy(),
            location.getProperties()
        );
        return eventProcessorHelper.processAndLogEvent(event)
        .thenApply(updatedItemProperties -> {
            java.net.URI url = java.net.URI.create("/api/locations/" + event.getEntityId());
            return ResponseEntity.created(url).body(updatedItemProperties);
        });
    }

    @PutMapping()
    public CompletableFuture<ResponseEntity<Map<String, Object>>> updateLocation(@RequestBody UpdateModel model) {
        var event = new LocationPropertiesUpdatedEvent(
            model.getId(),
            model.getProperties()
        );
        return eventProcessorHelper.processAndLogEvent(event)
        .thenApply(updatedItemProperties -> {
            return ResponseEntity.ok(updatedItemProperties);
        });
    }

    @DeleteMapping("/{id}")
    public CompletableFuture<ResponseEntity<Map<String, Object>>> deleteLocation(@PathVariable String id) {
        var event = new LocationDeletedEvent(id);
        return eventProcessorHelper.processAndLogEvent(event)
        .thenApply(result -> {
            return ResponseEntity.noContent().build();
        });
    }
}
