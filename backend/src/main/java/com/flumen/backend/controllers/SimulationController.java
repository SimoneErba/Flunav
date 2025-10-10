package com.flumen.backend.controllers;

import com.flumen.backend.services.SimulationService;
import com.flumen.backend.models.simulation.SimulationState;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;

import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/simulations")
@Tag(name = "Simulations", description = "APIs for creating and managing historical state simulations")
public class SimulationController {

    private final SimulationService simulationService;

    public SimulationController(SimulationService simulationService) {
        this.simulationService = simulationService;
    }

    @PostMapping
    public ResponseEntity<SimulationState> createSimulation(@RequestBody CreateSimulationRequest request) {
        SimulationState state = simulationService.createSimulation(request.timestamp());
        return ResponseEntity.accepted().body(state);
    }

    @GetMapping("/{simulationId}")
    public ResponseEntity<SimulationState> getSimulationStatus(@PathVariable String simulationId) {
        return ResponseEntity.ok(simulationService.getSimulationState(simulationId));
    }

    @DeleteMapping("/{simulationId}")
    public ResponseEntity<Void> destroySimulation(@PathVariable String simulationId) {
        simulationService.destroySimulation(simulationId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{simulationId}/playback/start")
    public ResponseEntity<Void> startPlayback(@PathVariable String simulationId, @RequestBody PlaybackRequest request) {
        simulationService.startPlayback(simulationId, request.speedFactor());
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/{simulationId}/playback/cancel")
    public ResponseEntity<Void> cancelPlayback(@PathVariable String simulationId) {
        simulationService.cancelPlayback(simulationId);
        return ResponseEntity.accepted().build();
    }
    
    // --- DTOs (can be inner records or separate files) ---
    public record CreateSimulationRequest(Instant timestamp) {}
    public record PlaybackRequest(double speedFactor) {}
}