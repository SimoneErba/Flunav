package com.flunav.backend.controllers;

import com.flunav.backend.config.BlockInDemo;
import com.flunav.backend.services.DestinationMappingService;
import com.flunav.backend.utils.ControllerHelper;
import flunav.events.DestinationMappingRecord;
import flunav.events.MapDestinationsEvent;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/destination-mappings")
@PreAuthorize("hasAnyRole('ADMIN','SUPERADMIN')")
public class DestinationMappingController {

    private final DestinationMappingService destinationMappingService;
    private final ControllerHelper controllerHelper;

    public DestinationMappingController(
            DestinationMappingService destinationMappingService,
            ControllerHelper controllerHelper) {
        this.destinationMappingService = destinationMappingService;
        this.controllerHelper = controllerHelper;
    }

    @GetMapping
    public List<DestinationMappingRecord> getDestinationMappings() {
        return destinationMappingService.getDestinationMappings();
    }

    @BlockInDemo
    @PutMapping
    public CompletableFuture<ResponseEntity<List<DestinationMappingRecord>>> updateDestinationMappings(
            @RequestBody List<DestinationMappingRecord> mappings) {
        return controllerHelper.processAndLogEvent(new MapDestinationsEvent(null, mappings))
                .thenApply(result -> ResponseEntity.ok(destinationMappingService.getDestinationMappings()));
    }
}
