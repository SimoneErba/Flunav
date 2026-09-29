package com.flunav.backend.controllers;

import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import com.flunav.backend.models.comparison.SimulationComparison;
import com.flunav.backend.models.response.MultiSimulationResponse;
import com.flunav.backend.services.SimulationComparisonService;

@RestController
@RequestMapping("/api/simulation-comparisons")
public class SimulationComparisonController {
    private final SimulationComparisonService comparisons;
    public SimulationComparisonController(SimulationComparisonService comparisons) { this.comparisons = comparisons; }
    @GetMapping
    public List<SimulationComparison> listSimulationComparisons() { return comparisons.list(); }
    @GetMapping("/{id}")
    public SimulationComparison getSimulationComparison(@PathVariable String id) { return comparisons.get(id); }
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERADMIN')")
    public SimulationComparison createSimulationComparison(@RequestBody SimulationComparison.Request request) { return comparisons.create(request); }
    @PostMapping("/import")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERADMIN')")
    public SimulationComparison importSimulationComparison(@RequestBody Bundle bundle) {
        return comparisons.create(new SimulationComparison.Request(bundle.definition().name(), bundle.definition().alternatives()));
    }

    @PostMapping("/{id}/run")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERADMIN')")
    public ResponseEntity<SimulationComparison> runSimulationComparison(@PathVariable String id) { return ResponseEntity.accepted().body(comparisons.run(id)); }
    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERADMIN')")
    public ResponseEntity<SimulationComparison> cancelSimulationComparison(@PathVariable String id) { return ResponseEntity.accepted().body(comparisons.cancel(id)); }
    @PostMapping("/{id}/rerun")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERADMIN')")
    public SimulationComparison rerunSimulationComparison(@PathVariable String id) {
        var definition = comparisons.get(id);
        return comparisons.create(new SimulationComparison.Request(definition.name(), definition.alternatives()));
    }

    @GetMapping("/{id}/summary.csv")
    public ResponseEntity<String> exportComparisonSummary(@PathVariable String id) {
        return ResponseEntity.ok().header("Content-Disposition", "attachment; filename=\"comparison-summary.csv\"")
                .body(comparisons.summaryCsv(id));
    }

    @GetMapping("/{id}/progress")
    public List<MultiSimulationResponse> getComparisonProgress(@PathVariable String id) { return comparisons.progress(id).stream().map(MultiSimulationResponse::from).toList(); }
    @GetMapping("/{id}/report")
    public SimulationComparisonService.ComparisonReport getComparisonReport(@PathVariable String id) { return comparisons.report(id); }
    @GetMapping("/{id}/export")
    public ResponseEntity<?> exportSimulationComparison(@PathVariable String id, @RequestParam(defaultValue = "json") String format) {
        if ("csv".equals(format)) return ResponseEntity.ok().header("Content-Disposition", "attachment; filename=\"comparison.csv\"").body(comparisons.csv(id));
        if (!"json".equals(format)) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "format must be json or csv");
        return ResponseEntity.ok().header("Content-Disposition", "attachment; filename=\"comparison.json\"").body(new Bundle(comparisons.get(id), comparisons.report(id)));
    }
    public record Bundle(SimulationComparison definition, SimulationComparisonService.ComparisonReport results) {}
}
