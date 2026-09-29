package com.flunav.backend.controllers;

import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import com.flunav.backend.models.scenario.ConveyorPreset;
import com.flunav.backend.services.ConveyorPresetService;

@RestController
@RequestMapping("/api/conveyor-presets")
public class ConveyorPresetController {
    private final ConveyorPresetService presets;
    public ConveyorPresetController(ConveyorPresetService presets) { this.presets = presets; }
    @GetMapping
    public List<ConveyorPreset> listConveyorPresets() { return presets.list(); }
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERADMIN')")
    public ConveyorPreset saveConveyorPreset(@RequestBody ConveyorPreset preset) { return presets.save(preset); }
}
