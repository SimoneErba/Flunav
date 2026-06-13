package com.flunav.backend.controllers;

import com.flunav.backend.config.BlockInDemo;
import com.flunav.backend.services.DestinationExitMappingService;
import com.flunav.backend.utils.ControllerHelper;
import flunav.events.DestinationExitMappingRecord;
import flunav.events.MapDestinationExitsEvent;
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
@RequestMapping("/api/destination-exit-mappings")
@PreAuthorize("hasAnyRole('ADMIN','SUPERADMIN')")
public class DestinationExitMappingController {
    private final DestinationExitMappingService mappingService;
    private final ControllerHelper controllerHelper;

    public DestinationExitMappingController(
            DestinationExitMappingService mappingService,
            ControllerHelper controllerHelper) {
        this.mappingService = mappingService;
        this.controllerHelper = controllerHelper;
    }

    @GetMapping
    public List<DestinationExitMappingRecord> getMappings() {
        return mappingService.getMappings();
    }

    @BlockInDemo
    @PutMapping
    public CompletableFuture<ResponseEntity<List<DestinationExitMappingRecord>>> updateMappings(
            @RequestBody List<DestinationExitMappingRecord> mappings) {
        return controllerHelper.processAndLogEvent(new MapDestinationExitsEvent(mappings))
                .thenApply(result -> ResponseEntity.ok(mappingService.getMappings()));
    }
}
