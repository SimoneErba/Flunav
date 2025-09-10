package com.flumen.backend.controllers;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.flumen.backend.models.input.ConnectionInput;
import com.flumen.backend.utils.ControllerHelper;

import flumen.events.ConnectionDeletedEvent;
import flumen.events.LocationConnectionCreatedEvent;

@RestController
@RequestMapping("/api/connections")
public class LocationConnectionController {

    private final ControllerHelper eventProcessorHelper;

    @Autowired
    public LocationConnectionController(ControllerHelper eventProcessorHelper) {
        this.eventProcessorHelper = eventProcessorHelper;
    }

    /**
     * Creates a connection between two locations.
     * @param connectionInput The connection details (from location -> to location).
     * @return A response indicating success or failure.
     */
    @PostMapping
    public void createConnection(@RequestBody ConnectionInput connectionInput) {
        var event = new LocationConnectionCreatedEvent(
            connectionInput.getLocation1Id(),
            connectionInput.getLocation2Id()
        );
        eventProcessorHelper.processAndLogEvent(event)
                .thenApply(connectionResult -> {

            java.net.URI connection = java.net.URI.create("/api/connections/" + event.getEntityId());
            return ResponseEntity.created(connection).body(connectionResult);
        });
    }

    @DeleteMapping
    public CompletableFuture<ResponseEntity<Map<String, Object>>> deleteConnection(String sourceId, String targetId) {
        var event = new ConnectionDeletedEvent(sourceId, targetId);
        return eventProcessorHelper.processAndLogEvent(event)
        .thenApply(result -> {
            return ResponseEntity.noContent().build();
        });
    }
}
