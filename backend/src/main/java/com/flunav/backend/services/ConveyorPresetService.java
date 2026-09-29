package com.flunav.backend.services;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import com.flunav.backend.models.scenario.ConveyorPreset;
import com.flunav.backend.repositories.ConveyorPresetRepository;
import flunav.types.ConveyorType;

@Service
public class ConveyorPresetService {
    private final ConveyorPresetRepository repository;
    public ConveyorPresetService(ConveyorPresetRepository repository) { this.repository = repository; }
    public List<ConveyorPreset> list() {
        var values = new java.util.ArrayList<>(List.of(
                preset("belt", ConveyorType.BELT, 3, .5, .05, 100),
                preset("roller", ConveyorType.ROLLER, 3, .5, .05, 100),
                preset("chute", ConveyorType.CHUTE, 3, 1, 0, 10),
                preset("staging", ConveyorType.STAGING, 10, 1, .1, 1)));
        values.addAll(repository.list());
        return List.copyOf(values);
    }
    public ConveyorPreset save(ConveyorPreset input) {
        if (input == null || input.name() == null || input.name().isBlank() || input.type() == null
                || !Double.isFinite(input.length()) || input.length() <= 0
                || !Double.isFinite(input.speed()) || input.speed() < 0
                || !Double.isFinite(input.minDistance()) || input.minDistance() < 0
                || input.capacity() != null && input.capacity() <= 0
                || input.properties() != null && !input.properties().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Valid name, physics and capacity are required; v1 presets have no custom properties");
        }
        var saved = new ConveyorPreset(UUID.randomUUID().toString(), 1, input.name().trim(), input.description(),
                input.type(), input.length(), input.speed(), input.minDistance(), input.capacity(), input.mainPath(), Map.of());
        repository.insert(saved);
        return saved;
    }
    private ConveyorPreset preset(String id, ConveyorType type, double length, double speed, double gap, Integer capacity) {
        return new ConveyorPreset(id, 1, type.name(), "Illustrative default: " + length + " m, " + speed
                + " m/s, " + gap + " m gap; no manufacturer validation.", type, length, speed, gap, capacity, true, Map.of());
    }
}
