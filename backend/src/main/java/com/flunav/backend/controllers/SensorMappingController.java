package com.flunav.backend.controllers;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;

import com.flunav.backend.config.BlockInDemo;
import com.flunav.backend.services.SensorMappingService;
import com.flunav.backend.utils.ControllerHelper;

import flunav.events.MapSensorMappingsEvent;
import flunav.events.SensorMappingRecord;

@RestController
@RequestMapping("/api/sensor-mappings")
@PreAuthorize("hasAnyRole('ADMIN','SUPERADMIN')")
public class SensorMappingController {
    private final SensorMappingService sensorMappingService;
    private final ControllerHelper controllerHelper;

    public SensorMappingController(SensorMappingService sensorMappingService, ControllerHelper controllerHelper) {
        this.sensorMappingService = sensorMappingService;
        this.controllerHelper = controllerHelper;
    }

    @GetMapping
    @Operation(operationId = "getSensorMappings")
    public List<SensorMappingRecord> getMappings() {
        return sensorMappingService.getMappings();
    }

    @BlockInDemo
    @PutMapping
    @Operation(operationId = "updateSensorMappings")
    public CompletableFuture<ResponseEntity<List<SensorMappingRecord>>> updateMappings(
            @RequestBody List<SensorMappingRecord> mappings) {
        return controllerHelper.processAndLogEvent(new MapSensorMappingsEvent(mappings))
                .thenApply(result -> ResponseEntity.ok(sensorMappingService.getMappings()));
    }
}
