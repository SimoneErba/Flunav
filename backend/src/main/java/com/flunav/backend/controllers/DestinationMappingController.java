package com.flunav.backend.controllers;

import com.flunav.backend.config.BlockInDemo;
import com.flunav.backend.services.DestinationMappingService;
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

@RestController
@RequestMapping("/api/destination-mappings")
@PreAuthorize("hasAnyRole('ADMIN','SUPERADMIN')")
public class DestinationMappingController {

    private final DestinationMappingService destinationMappingService;

    public DestinationMappingController(DestinationMappingService destinationMappingService) {
        this.destinationMappingService = destinationMappingService;
    }

    @GetMapping
    public List<DestinationMappingRecord> getDestinationMappings() {
        return destinationMappingService.getDestinationMappings();
    }

    @BlockInDemo
    @PutMapping
    public ResponseEntity<List<DestinationMappingRecord>> updateDestinationMappings(
            @RequestBody List<DestinationMappingRecord> mappings) {
        destinationMappingService.saveMapDestinations(new MapDestinationsEvent(null, mappings));
        return ResponseEntity.ok(destinationMappingService.getDestinationMappings());
    }
}
