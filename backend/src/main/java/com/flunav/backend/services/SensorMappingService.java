package com.flunav.backend.services;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flunav.backend.repositories.support.RedisKeyNamespace;

import flunav.events.MapSensorMappingsEvent;
import flunav.events.SensorMappingRecord;

/**
 * Stores external sensor aliases as an atomic table in the active live or
 * simulation Redis namespace.
 */
@Service
public class SensorMappingService {
    private static final String TABLE_KEY = "sensor_map:records";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final TopologyProvider topologyProvider;

    public SensorMappingService(StringRedisTemplate redis, ObjectMapper objectMapper, TopologyProvider topologyProvider) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.topologyProvider = topologyProvider;
    }

    public void saveMappings(MapSensorMappingsEvent event) {
        List<SensorMappingRecord> mappings = normalizeAndValidate(event);
        if (mappings.isEmpty()) {
            redis.delete(tableKey());
            return;
        }
        try {
            redis.opsForValue().set(tableKey(), objectMapper.writeValueAsString(mappings));
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialize sensor mappings", e);
        }
    }

    public List<SensorMappingRecord> getMappings() {
        String json = redis.opsForValue().get(tableKey());
        if (json == null) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<SensorMappingRecord>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("Failed to deserialize sensor mappings", e);
        }
    }

    /** Resolves a sensor alias without treating the alias as a topology identifier. */
    public Optional<SensorMappingRecord> findBySensorName(String sensorName) {
        if (sensorName == null || sensorName.isBlank()) {
            return Optional.empty();
        }
        String normalized = sensorName.trim().toLowerCase(Locale.ROOT);
        return getMappings().stream()
                .filter(mapping -> mapping.getSensorName().equalsIgnoreCase(normalized))
                .findFirst();
    }

    private List<SensorMappingRecord> normalizeAndValidate(MapSensorMappingsEvent event) {
        if (event == null || event.getMappings() == null) {
            throw new IllegalArgumentException("mappings are required");
        }

        Set<String> sensorNames = new HashSet<>();
        List<SensorMappingRecord> normalized = new ArrayList<>();
        for (SensorMappingRecord mapping : event.getMappings()) {
            if (mapping == null || isBlank(mapping.getSensorName())) {
                throw new IllegalArgumentException("sensorName is required");
            }
            if (isBlank(mapping.getConveyorId())) {
                throw new IllegalArgumentException("conveyorId is required");
            }
            if (mapping.getProgress() == null || !Double.isFinite(mapping.getProgress())
                    || mapping.getProgress() < 0 || mapping.getProgress() > 100) {
                throw new IllegalArgumentException("progress must be a finite percentage between 0 and 100");
            }

            String sensorName = mapping.getSensorName().trim();
            String normalizedSensorName = sensorName.toLowerCase(Locale.ROOT);
            if (!sensorNames.add(normalizedSensorName)) {
                throw new IllegalArgumentException("duplicate sensorName mapping row");
            }

            String conveyorId = mapping.getConveyorId().trim();
            try {
                topologyProvider.getConveyorById(conveyorId);
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("conveyorId does not identify an existing conveyor: " + conveyorId, e);
            }
            normalized.add(new SensorMappingRecord(sensorName, conveyorId, mapping.getProgress()));
        }
        return normalized;
    }

    private String tableKey() {
        return RedisKeyNamespace.current(TABLE_KEY);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
