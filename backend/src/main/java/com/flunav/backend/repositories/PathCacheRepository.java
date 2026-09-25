package com.flunav.backend.repositories;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flunav.backend.repositories.support.RedisKeyNamespace;
import com.flunav.backend.repositories.support.MultiSimulationRuntimeStore;
import flunav.types.PositionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Repository
public class PathCacheRepository {
    private static final Logger logger = LoggerFactory.getLogger(PathCacheRepository.class);
    private static final String AVAILABLE_KEY = "pathcache:v1:available";
    private static final String SHORTEST_KEY = "pathcache:v1:shortest";
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final MultiSimulationRuntimeStore runtimeStore;

    public PathCacheRepository(StringRedisTemplate redis, ObjectMapper objectMapper,
            MultiSimulationRuntimeStore runtimeStore) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.runtimeStore = runtimeStore;
    }

    /**
     * Reads an available-route path from the active live or simulation namespace.
     * Available-route entries include travel time because priority routing uses it
     * to compare candidate exits without recalculating graph cost.
     */
    public Optional<AvailablePath> getAvailablePath(String sourceLocationId, String exitId) {
        String cached = readHashValue(currentKey(AVAILABLE_KEY), cacheField(sourceLocationId, exitId));
        if (cached == null) {
            return Optional.empty();
        }

        try {
            AvailablePath value = objectMapper.readValue(cached, AvailablePath.class);
            if (value.locations() == null || value.locations().isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new AvailablePath(List.copyOf(value.locations()), value.travelSeconds()));
        } catch (Exception e) {
            logger.warn("Ignoring unreadable available path cache entry from {} to {}", sourceLocationId, exitId, e);
            return Optional.empty();
        }
    }

    public void putAvailablePath(String sourceLocationId, String exitId, List<String> locations,
            double travelSeconds) {
        if (locations == null || locations.isEmpty() || !Double.isFinite(travelSeconds)) {
            return;
        }
        writeHashValue(
                currentKey(AVAILABLE_KEY),
                cacheField(sourceLocationId, exitId),
                new AvailablePath(List.copyOf(locations), travelSeconds));
    }

    /**
     * Reads a shortest location path from the active namespace.
     * The source position type is part of the key because conveyor sources resolve
     * from their target location while location sources start at the location.
     */
    public Optional<List<String>> getShortestPath(String sourceId, PositionType sourceType, String destinationId) {
        String cached = readHashValue(currentKey(SHORTEST_KEY), cacheField(sourceId, sourceType, destinationId));
        if (cached == null) {
            return Optional.empty();
        }

        try {
            List<String> path = objectMapper.readValue(cached, STRING_LIST);
            return path == null || path.isEmpty() ? Optional.empty() : Optional.of(List.copyOf(path));
        } catch (Exception e) {
            logger.warn("Ignoring unreadable shortest path cache entry from {} ({}) to {}",
                    sourceId, sourceType, destinationId, e);
            return Optional.empty();
        }
    }

    public void putShortestPath(String sourceId, PositionType sourceType, String destinationId, List<String> path) {
        if (path == null || path.isEmpty()) {
            return;
        }
        writeHashValue(currentKey(SHORTEST_KEY), cacheField(sourceId, sourceType, destinationId), List.copyOf(path));
    }

    /**
     * Clears only the live or simulation namespace currently active on this thread.
     * Topology reducers call this after a successful change so other simulations
     * keep caches built against their own isolated graph.
     */
    public void invalidateCurrentNamespace() {
        var memory = runtimeStore.current();
        List<String> keys = List.of(currentKey(AVAILABLE_KEY), currentKey(SHORTEST_KEY));
        if (memory != null) memory.delete(keys); else redis.delete(keys);
    }

    public void cleanupSimulationData(String simulationId) {
        if (simulationId == null || simulationId.isBlank()) {
            return;
        }
        List<String> keys = List.of(simulationKey(simulationId, AVAILABLE_KEY), simulationKey(simulationId, SHORTEST_KEY));
        var memory = runtimeStore.get(simulationId);
        if (memory != null) memory.delete(keys); else redis.delete(keys);
    }

    private String readHashValue(String key, String field) {
        var memory = runtimeStore.current();
        if (memory != null) return memory.hashGet(key, field);
        Object value = redis.opsForHash().get(key, field);
        return value instanceof String text ? text : null;
    }

    private void writeHashValue(String key, String field, Object value) {
        try {
            String json = objectMapper.writeValueAsString(value);
            var memory = runtimeStore.current();
            if (memory != null) memory.hashPut(key, field, json); else redis.opsForHash().put(key, field, json);
        } catch (Exception e) {
            logger.warn("Failed to write path cache entry {}:{}", key, field, e);
        }
    }

    private String currentKey(String baseKey) {
        return RedisKeyNamespace.current(baseKey);
    }

    private String simulationKey(String simulationId, String baseKey) {
        return RedisKeyNamespace.simulation(simulationId, baseKey);
    }

    private String cacheField(Object... parts) {
        StringBuilder field = new StringBuilder();
        for (Object part : parts) {
            if (!field.isEmpty()) {
                field.append('|');
            }
            field.append(encode(part));
        }
        return field.toString();
    }

    private String encode(Object value) {
        String normalized = value instanceof PositionType type
                ? type.name()
                : String.valueOf(value);
        normalized = normalized.toLowerCase(Locale.ROOT);
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(normalized.getBytes(StandardCharsets.UTF_8));
    }

    public record AvailablePath(List<String> locations, double travelSeconds) {
    }
}
