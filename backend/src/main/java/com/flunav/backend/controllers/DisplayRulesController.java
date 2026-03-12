package com.flunav.backend.controllers;

import flunav.types.DisplayRule;

import com.flunav.backend.config.BlockInDemo;
import com.flunav.backend.models.response.DisplayRuleColorResult;
import com.flunav.backend.services.DisplayRulesService;
import com.flunav.backend.services.GraphService;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/display-rules")
public class DisplayRulesController {

    private final DisplayRulesService displayRulesService;
    private final GraphService graphService;

    public DisplayRulesController(DisplayRulesService displayRulesService, GraphService graphService) {
        this.displayRulesService = displayRulesService;
        this.graphService = graphService;
    }

    @GetMapping
    public List<DisplayRule> getDisplayRules() {
        return displayRulesService.getDisplayRules();
    }

    @BlockInDemo
    @PutMapping
    public ResponseEntity<DisplayRuleColorResult> updateDisplayRules(@RequestBody List<DisplayRule> rules) {
        displayRulesService.updateDisplayRules(rules);
        DisplayRuleColorResult colors = graphService.computeColors(rules);
        return ResponseEntity.ok(colors);
    }
}
