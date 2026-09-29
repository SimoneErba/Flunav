package com.flunav.backend.models.scenario;

import java.util.Map;
import flunav.types.ConveyorType;

public record ConveyorPreset(String id, int version, String name, String description,
        ConveyorType type, double length, double speed, double minDistance, Integer capacity,
        boolean mainPath, Map<String, Object> properties) {}
