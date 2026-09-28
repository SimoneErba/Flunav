package com.flunav.backend.controllers;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.flunav.backend.services.ClientDemoScenarioService;
import com.flunav.backend.services.AirportRoutingDemoScenarioService;
import com.flunav.backend.services.ConveyorSpacingDemoScenarioService;
import com.flunav.backend.models.response.SimulationStateResponse;

@RestController
@RequestMapping("/api/client-demo")
public class ClientDemoScenarioController {
    private final ClientDemoScenarioService demo;
    private final AirportRoutingDemoScenarioService airportDemo;
    private final ConveyorSpacingDemoScenarioService spacingDemo;

    public ClientDemoScenarioController(ClientDemoScenarioService demo,
            AirportRoutingDemoScenarioService airportDemo, ConveyorSpacingDemoScenarioService spacingDemo) {
        this.demo = demo;
        this.airportDemo = airportDemo;
        this.spacingDemo = spacingDemo;
    }

    @PreAuthorize("hasAnyRole('ADMIN','SUPERADMIN')")
    @PostMapping
    public ResponseEntity<DemoStartResponse> start() {
        try {
            ClientDemoScenarioService.DemoStatus status = demo.start();
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(new DemoStartResponse(status, new SimulationStateResponse(demo.simulationState())));
        } catch (IllegalStateException error) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, error.getMessage());
        }
    }

    @PreAuthorize("hasAnyRole('ADMIN','SUPERADMIN')")
    @GetMapping
    public ClientDemoScenarioService.DemoStatus status() {
        return demo.status();
    }

    @PreAuthorize("hasAnyRole('ADMIN','SUPERADMIN')")
    @PostMapping("/airport-routing")
    public ResponseEntity<AirportDemoStartResponse> startAirportRouting() {
        try {
            AirportRoutingDemoScenarioService.DemoStatus status = airportDemo.start();
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(new AirportDemoStartResponse(status,
                            new SimulationStateResponse(airportDemo.simulationState())));
        } catch (IllegalStateException error) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, error.getMessage());
        }
    }

    @PreAuthorize("hasAnyRole('ADMIN','SUPERADMIN')")
    @GetMapping("/airport-routing")
    public AirportRoutingDemoScenarioService.DemoStatus airportRoutingStatus() {
        return airportDemo.status();
    }

    @PreAuthorize("hasAnyRole('ADMIN','SUPERADMIN')")
    @PostMapping("/conveyor-spacing")
    public ResponseEntity<SimulationStateResponse> startConveyorSpacing() {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new SimulationStateResponse(spacingDemo.start()));
    }

    public record DemoStartResponse(
            ClientDemoScenarioService.DemoStatus demo,
            SimulationStateResponse simulation) {
    }

    public record AirportDemoStartResponse(
            AirportRoutingDemoScenarioService.DemoStatus demo,
            SimulationStateResponse simulation) {
    }
}
