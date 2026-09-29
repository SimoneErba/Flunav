package com.flunav.backend.controllers;

import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import com.flunav.backend.models.scenario.ScenarioDocument;
import com.flunav.backend.models.scenario.SavedScenario;
import com.flunav.backend.models.response.SimulationStateResponse;
import com.flunav.backend.services.ScenarioService;

@RestController
@RequestMapping("/api/scenarios")
public class ScenarioController {
    private final ScenarioService scenarios;
    public ScenarioController(ScenarioService scenarios) { this.scenarios = scenarios; }

    @GetMapping
    public List<SavedScenario> listScenarios() { return scenarios.list(); }

    @GetMapping("/{id}")
    public SavedScenario getScenario(@PathVariable String id, @RequestParam(required = false) Integer revision) {
        return scenarios.get(id, revision);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERADMIN')")
    public SavedScenario saveScenario(@RequestBody ScenarioDocument document) { return scenarios.save(document, null); }

    @PostMapping("/{id}/revisions")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERADMIN')")
    public SavedScenario saveScenarioRevision(@PathVariable String id, @RequestBody ScenarioDocument document) {
        return scenarios.save(document, id);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERADMIN')")
    public SavedScenario renameScenario(@PathVariable String id, @RequestBody Metadata metadata) {
        var old = scenarios.get(id, null).document();
        return scenarios.save(new ScenarioDocument(old.format(), old.schemaVersion(), old.modelVersion(), old.generatorVersion(),
                metadata.name(), metadata.description(), old.exportedAt(), old.applicationVersion(), old.units(),
                old.baseline(), old.configuration(), old.experiment(), old.provenance(), old.results()), id);
    }

    @PostMapping("/{id}/duplicate")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERADMIN')")
    public SavedScenario duplicateScenario(@PathVariable String id, @RequestParam(required = false) Integer revision) {
        return scenarios.save(scenarios.get(id, revision).document(), null);
    }

    @PostMapping(value = "/import/validate", consumes = "application/json")
    public ScenarioDocument validateScenarioImport(@RequestBody byte[] file) { return scenarios.parse(file); }

    @PostMapping(value = "/import", consumes = "application/json")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERADMIN')")
    public SavedScenario importScenario(@RequestBody byte[] file) { return scenarios.save(scenarios.parse(file), null); }

    @GetMapping("/capture")
    public ScenarioDocument captureScenario(@RequestParam(defaultValue = "Scenario") String name,
            @RequestParam(defaultValue = "") String description, @RequestParam(defaultValue = "false") boolean includeItems) {
        return scenarios.capture(name, description, includeItems);
    }

    @PostMapping("/{id}/open")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERADMIN')")
    public SimulationStateResponse openScenario(@PathVariable String id, @RequestParam(required = false) Integer revision) {
        return new SimulationStateResponse(scenarios.open(id, revision));
    }

    @PostMapping("/{id}/template")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERADMIN')")
    public SavedScenario saveScenarioAsTemplate(@PathVariable String id, @RequestParam(required = false) Integer revision) {
        return scenarios.saveReusableTemplate(id, revision);
    }

    @PostMapping("/{id}/experiment")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERADMIN')")
    public com.flunav.backend.models.response.MultiSimulationResponse createScenarioExperiment(@PathVariable String id,
            @RequestParam(required = false) Integer revision) {
        return com.flunav.backend.models.response.MultiSimulationResponse.from(scenarios.createExperiment(id, revision));
    }

    @GetMapping("/{id}/export")
    public ResponseEntity<ScenarioDocument> exportScenario(@PathVariable String id, @RequestParam(required = false) Integer revision) {
        return ResponseEntity.ok().header("Content-Disposition", "attachment; filename=\"scenario.flusim\"")
                .body(scenarios.get(id, revision).document());
    }

    public record Metadata(String name, String description) {}
}
