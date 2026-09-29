package com.flunav.backend.controllers;

import java.net.URI;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.flunav.backend.models.multisimulation.MultiSimulationConfiguration;
import com.flunav.backend.models.multisimulation.MultiSimulationReport;
import com.flunav.backend.models.multisimulation.MultiSimulationRun;
import com.flunav.backend.models.response.MultiSimulationResponse;
import com.flunav.backend.models.response.MultiSimulationEstimateResponse;
import com.flunav.backend.services.MultiSimulationService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/multi-simulations")
@Tag(name = "Multi-simulations")
public class MultiSimulationController {
    private final MultiSimulationService multiSimulationService;
    private final com.flunav.backend.services.ScenarioService scenarios;

    public MultiSimulationController(MultiSimulationService multiSimulationService, com.flunav.backend.services.ScenarioService scenarios) {
        this.scenarios = scenarios;
        this.multiSimulationService = multiSimulationService;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERADMIN')")
    @Operation(summary = "Capture the current topology and create a multi-simulation")
    public ResponseEntity<MultiSimulationResponse> create(
            @RequestBody MultiSimulationConfiguration configuration) {
        MultiSimulationResponse response = MultiSimulationResponse.from(multiSimulationService.create(configuration));
        return ResponseEntity.created(URI.create("/api/multi-simulations/" + response.id())).body(response);
    }

    @GetMapping
    public List<MultiSimulationResponse> list() {
        return multiSimulationService.list().stream().map(MultiSimulationResponse::from).toList();
    }

    @PostMapping("/estimate")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERADMIN')")
    @Operation(summary = "Estimate multi-simulation wall time using current topology and worker capacity")
    public MultiSimulationEstimateResponse estimate(@RequestBody MultiSimulationConfiguration configuration) {
        return multiSimulationService.estimate(configuration);
    }

    @GetMapping("/{id}")
    public MultiSimulationResponse get(@PathVariable String id) {
        return MultiSimulationResponse.from(multiSimulationService.get(id));
    }

    @GetMapping("/{id}/status")
    public MultiSimulationResponse status(@PathVariable String id) {
        return MultiSimulationResponse.from(multiSimulationService.get(id));
    }

    @PostMapping("/{id}/run")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERADMIN')")
    public ResponseEntity<MultiSimulationResponse> run(@PathVariable String id) {
        return ResponseEntity.accepted().body(MultiSimulationResponse.from(multiSimulationService.start(id)));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERADMIN')")
    public ResponseEntity<MultiSimulationResponse> cancel(@PathVariable String id) {
        return ResponseEntity.accepted().body(MultiSimulationResponse.from(multiSimulationService.cancel(id)));
    }

    @GetMapping("/{id}/runs")
    public List<MultiSimulationRun> runs(@PathVariable String id) {
        return multiSimulationService.runs(id);
    }

    @GetMapping("/{id}/export")
    public ResponseEntity<com.flunav.backend.models.scenario.ScenarioDocument> exportExperiment(@PathVariable String id,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "false") boolean includeResults) {
        return ResponseEntity.ok().header("Content-Disposition", "attachment; filename=\"experiment.flusim\"")
                .body(scenarios.exportExperiment(id, includeResults));
    }

    @GetMapping("/{id}/report")
    public MultiSimulationReport report(@PathVariable String id) {
        return multiSimulationService.report(id);
    }
}
