package com.flunav.backend.services;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flunav.backend.repositories.support.RedisKeyNamespace;
import com.flunav.backend.repositories.support.MultiSimulationRuntimeStore;
import com.flunav.backend.services.routing.MappingValueNormalizer;
import flunav.events.DestinationExitMappingRecord;
import flunav.events.MapDestinationExitsEvent;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Stores the second routing-mapping stage from logical destinations to physical
 * exit locations. Table replacement is atomic at the Redis-value level, and key
 * selection follows the current live or simulation context.
 */
@Service
public class DestinationExitMappingService {
    private static final String TABLE_KEY = "destination_exit_map:records";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final MultiSimulationRuntimeStore runtimeStore;

    public DestinationExitMappingService(StringRedisTemplate redis, ObjectMapper objectMapper,
            MultiSimulationRuntimeStore runtimeStore) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.runtimeStore = runtimeStore;
    }

    /**
     * Replaces the destination-to-exit table used by routing decisions.
     * The whole table is written as one Redis value so route selection never reads
     * a partially updated exit list.
     */
    public void saveMappings(MapDestinationExitsEvent event) {
        List<DestinationExitMappingRecord> mappings = normalizeAndValidate(event);
        if (mappings.isEmpty()) {
            var memory = runtimeStore.current();
            if (memory != null) memory.delete(tableKey()); else redis.delete(tableKey());
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(mappings);
            var memory = runtimeStore.current();
            if (memory != null) memory.setValue(tableKey(), json); else redis.opsForValue().set(tableKey(), json);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialize destination exit mappings", e);
        }
    }

    /**
     * Loads the active destination-to-exit table for the current context.
     * Deserialization failures are raised because invalid exit mappings would make
     * routing decisions unsafe.
     */
    public List<DestinationExitMappingRecord> getMappings() {
        var memory = runtimeStore.current();
        String json = memory != null ? memory.getValue(tableKey()) : redis.opsForValue().get(tableKey());
        if (json == null) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<DestinationExitMappingRecord>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("Failed to deserialize destination exit mappings", e);
        }
    }

    /**
     * Expands one logical destination into ordered physical exits.
     * Routing preserves this order while still applying capacity and priority
     * scoring to the resulting candidate exits.
     */
    public List<String> getExits(String destination) {
        if (destination == null) {
            return List.of();
        }
        return getMappings().stream()
                .filter(mapping -> destination.equals(mapping.getDestination()))
                .findFirst()
                .map(DestinationExitMappingRecord::getExits)
                .orElse(List.of());
    }

    /**
     * Validates exit mappings before they affect routing.
     * Duplicate destinations are rejected and exit order is preserved while blank
     * or repeated exit ids are removed from the stored table.
     */
    private List<DestinationExitMappingRecord> normalizeAndValidate(MapDestinationExitsEvent event) {
        if (event == null || event.getMappings() == null) {
            throw new IllegalArgumentException("mappings are required");
        }
        Set<String> destinations = new HashSet<>();
        List<DestinationExitMappingRecord> normalized = new ArrayList<>();
        for (DestinationExitMappingRecord mapping : event.getMappings()) {
            if (mapping == null || mapping.getDestination() == null || mapping.getDestination().isBlank()) {
                throw new IllegalArgumentException("destination is required");
            }
            String destination = mapping.getDestination().trim();
            if (!destinations.add(destination)) {
                throw new IllegalArgumentException("duplicate destination mapping row");
            }
            List<String> exits = MappingValueNormalizer.requiredOrderedValues(mapping.getExits(), "exits");
            normalized.add(new DestinationExitMappingRecord(destination, exits));
        }
        return normalized;
    }

    /**
     * Builds the mapping-table key from the active simulation context.
     * Simulation-specific exit mappings must stay isolated because they directly
     * determine which chutes can be selected.
     */
    private String tableKey() {
        return RedisKeyNamespace.current(TABLE_KEY);
    }
}
