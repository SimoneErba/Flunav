package com.flunav.backend.controllers;

import flunav.types.DisplayRule;

import com.flunav.backend.config.BlockInDemo;
import com.flunav.backend.services.DisplayRulesService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/display-rules")
public class DisplayRulesController {

    private final DisplayRulesService displayRulesService;

    public DisplayRulesController(DisplayRulesService displayRulesService) {
        this.displayRulesService = displayRulesService;
    }

    @GetMapping
    public List<DisplayRule> getDisplayRules() {
        return displayRulesService.getDisplayRules();
    }

    @BlockInDemo
    @PutMapping
    public void updateDisplayRules(@RequestBody List<DisplayRule> rules) {
        displayRulesService.updateDisplayRules(rules);
    }
}
