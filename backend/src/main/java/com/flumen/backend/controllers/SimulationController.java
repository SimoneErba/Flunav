package com.flumen.backend.controllers;

import com.flumen.backend.models.graph.GraphData;
import com.flumen.backend.models.response.SimulationStateResponse;
import com.flumen.backend.models.simulation.SimulationState;
import com.flumen.backend.models.simulation.SimulationStatus;
import com.flumen.backend.services.GraphService;
import com.flumen.backend.services.SimulationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.flumen.backend.context.DatabaseContextHolder;
import java.time.Instant;

@RestController
@RequestMapping("/api/simulations")
@Tag(name = "Simulations", description = "APIs for creating and managing historical state simulations")
public class SimulationController {

    private final SimulationService simulationService;
    private final GraphService graphService; // Aggiungiamo GraphService per i dati del grafo

    public SimulationController(SimulationService simulationService, GraphService graphService) {
        this.simulationService = simulationService;
        this.graphService = graphService;
    }

    /**
     * Data Transfer Objects (DTOs) for API requests.
     */
    public record CreateSimulationRequest(Instant timestamp) {}
    public record PlaybackRequest(double speedFactor) {}

    @PostMapping
    @Operation(summary = "Create a new simulation", description = "Initiates an asynchronous build of a historical graph state. Returns immediately with a simulation ID.")
    @ApiResponse(responseCode = "202", description = "Simulation build has been accepted for processing.")
    public ResponseEntity<SimulationStateResponse> createSimulation(@RequestBody CreateSimulationRequest request) {
        SimulationState state = simulationService.createSimulation(request.timestamp());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new SimulationStateResponse(state.getId(), state.getStatus()));
    }

    @GetMapping("/{simulationId}")
    @Operation(summary = "Get simulation status", description = "Poll this endpoint to check the build status (e.g., QUEUED, BUILDING, READY).")
    @ApiResponse(responseCode = "200", description = "Current state of the simulation.")
    @ApiResponse(responseCode = "404", description = "Simulation not found.")
    public ResponseEntity<SimulationStateResponse> getSimulationStatus(
            @Parameter(description = "The unique ID of the simulation") @PathVariable String simulationId) {
        var state = simulationService.getSimulationState(simulationId);
        return ResponseEntity.ok(new SimulationStateResponse(state.getId(), state.getStatus()));
    }

    @GetMapping("/{simulationId}/graph")
    @Operation(summary = "Get graph data for a simulation", description = "Fetches the entire graph state for a specific simulation. Only works if the simulation status is READY or PLAYING.")
    @ApiResponse(responseCode = "200", description = "The graph data for the simulation.")
    @ApiResponse(responseCode = "404", description = "Simulation not found.")
    @ApiResponse(responseCode = "409", description = "Simulation is not in a READY state.")
    public ResponseEntity<GraphData> getSimulationGraphData(
            @Parameter(description = "The ID of the simulation") @PathVariable String simulationId) {
        
        SimulationState state = simulationService.getSimulationState(simulationId);
        if (state.getStatus() != SimulationStatus.READY && state.getStatus() != SimulationStatus.PLAYING) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }

        DatabaseContextHolder.setSimulationId(simulationId);
        try {
            GraphData data = graphService.getGraphData();
            return ResponseEntity.ok(data);
        } finally {
            DatabaseContextHolder.clear();
        }
    }

    @DeleteMapping("/{simulationId}")
    @Operation(summary = "Destroy a simulation", description = "Deletes an in-memory simulation and cancels any associated tasks.")
    @ApiResponse(responseCode = "204", description = "Simulation successfully destroyed.")
    public ResponseEntity<Void> destroySimulation(
            @Parameter(description = "The unique ID of the simulation") @PathVariable String simulationId) {
        simulationService.destroySimulation(simulationId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{simulationId}/playback/start")
    @Operation(summary = "Start event playback", description = "Starts the time-synchronized event playback for a READY simulation.")
    @ApiResponse(responseCode = "202", description = "Playback has been accepted and started.")
    @ApiResponse(responseCode = "409", description = "Simulation is not in a READY state.")
    public ResponseEntity<Void> startPlayback(
            @Parameter(description = "The ID of the simulation") @PathVariable String simulationId,
            @RequestBody PlaybackRequest request) {
        try {
            simulationService.startPlayback(simulationId, request.speedFactor());
            return ResponseEntity.accepted().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
    }

    @PostMapping("/{simulationId}/playback/cancel")
    @Operation(summary = "Cancel event playback", description = "Stops a currently running event playback.")
    @ApiResponse(responseCode = "202", description = "Cancellation signal has been sent.")
    public ResponseEntity<Void> cancelPlayback(
            @Parameter(description = "The ID of the simulation") @PathVariable String simulationId) {
        simulationService.cancelPlayback(simulationId);
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/{simulationId}/heartbeat")
    @Operation(summary = "Send a heartbeat", description = "A lightweight endpoint for the frontend to periodically call to keep a simulation alive.")
    @ApiResponse(responseCode = "204", description = "Heartbeat received.")
    public ResponseEntity<Void> sendHeartbeat(
            @Parameter(description = "The ID of the active simulation") @PathVariable String simulationId) {
        simulationService.getSimulationState(simulationId);
        return ResponseEntity.noContent().build();
    }
}