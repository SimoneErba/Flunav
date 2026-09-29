package com.flunav.backend.controllers;

import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import com.flunav.backend.models.scenario.ScenarioDocument;
import com.flunav.backend.services.SimulationTemplateService;

@RestController
@RequestMapping("/api/simulation-templates")
public class SimulationTemplateController {
    private final SimulationTemplateService templates;
    public SimulationTemplateController(SimulationTemplateService templates) { this.templates = templates; }
    @GetMapping
    public List<ScenarioDocument> listSimulationTemplates() { return templates.list(); }
    @GetMapping("/{id}")
    public ScenarioDocument getSimulationTemplate(@PathVariable String id) { return templates.get(id); }
    @PostMapping("/{id}/instantiate")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERADMIN')")
    public SimulationTemplateService.TemplateInstance instantiateSimulationTemplate(@PathVariable String id) { return templates.instantiate(id); }
}
