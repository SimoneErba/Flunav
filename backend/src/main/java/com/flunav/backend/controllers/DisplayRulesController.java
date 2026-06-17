package com.flunav.backend.controllers;

import flunav.types.DisplayRule;

import com.flunav.backend.config.BlockInDemo;
import com.flunav.backend.models.response.DisplayRuleColorResult;
import com.flunav.backend.services.DisplayRulesService;
import com.flunav.backend.utils.ControllerHelper;
import flunav.events.MapDisplayRulesEvent;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/display-rules")
public class DisplayRulesController {

    private final DisplayRulesService displayRulesService;
    private final ControllerHelper controllerHelper;

    public DisplayRulesController(DisplayRulesService displayRulesService, ControllerHelper controllerHelper) {
        this.displayRulesService = displayRulesService;
        this.controllerHelper = controllerHelper;
    }

    @GetMapping
    public List<DisplayRule> getDisplayRules() {
        return displayRulesService.getDisplayRules();
    }

    @BlockInDemo
    @PutMapping
    public CompletableFuture<ResponseEntity<DisplayRuleColorResult>> updateDisplayRules(@RequestBody List<DisplayRule> rules) {
        return controllerHelper.processAndLogEvent(new MapDisplayRulesEvent(rules))
                .thenApply(result -> {
                    DisplayRuleColorResult colors = (DisplayRuleColorResult) result.get("colors");
                    return ResponseEntity.ok(colors);
                });
    }
}
