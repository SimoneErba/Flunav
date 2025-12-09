package com.fiumen.backend.repositories;

import com.fiumen.backend.context.DatabaseContextHolder; // Import Context
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Repository
public class LiveItemRepository {
    private static final Logger logger = LoggerFactory.getLogger(LiveItemRepository.class);
    private static final long PATH_TTL_MINUTES = 5;

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public LiveItemRepository(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    // --- MULTI-TENANCY HELPER ---

    /**
     * Prefixes the Redis key with the Simulation ID if one exists in the current
     * context.
     * Live: "item:123"
     * Sim: "sim:abc-123:item:123"
     */
    private String getNamespacedKey(String baseKey) {
        String simId = DatabaseContextHolder.getSimulationId();
        if (simId != null) {
            return "sim:" + simId + ":" + baseKey;
        }
        return baseKey;
    }

    // --- WRITE OPERATIONS ---

    public void saveItemState(String itemId, String edgeId, Instant entryTime, String destId, String name) {
        // Apply namespace
        String itemKey = getNamespacedKey("item:" + itemId);
        String setKey = getNamespacedKey("sys:active_items");

        Map<String, String> data = new HashMap<>();
        data.put("e", edgeId);
        data.put("t", String.valueOf(entryTime.toEpochMilli()));

        // Optional Destination
        if (destId != null) {
            data.put("d", destId);
        }

        // Optional Name
        if (name != null) {
            data.put("n", name);
        }

        // 1. Save the Hash
        redis.opsForHash().putAll(itemKey, data);

        // 2. Add to the "Active Set" index
        redis.opsForSet().add(setKey, itemId);
    }

    public void updatePosition(String itemId, String newEdgeId, Instant entryTime) {
        String itemKey = getNamespacedKey("item:" + itemId);

        // We only update the specific fields that changed
        redis.opsForHash().put(itemKey, "e", newEdgeId);
        redis.opsForHash().put(itemKey, "t", String.valueOf(entryTime.toEpochMilli()));
    }

    public void updateName(String itemId, String name) {
        String itemKey = getNamespacedKey("item:" + itemId);
        redis.opsForHash().put(itemKey, "n", name);
    }

    public void deleteItem(String itemId) {
        String itemKey = getNamespacedKey("item:" + itemId);
        String setKey = getNamespacedKey("sys:active_items");

        redis.delete(itemKey);
        redis.opsForSet().remove(setKey, itemId);
    }

    // --- READ OPERATIONS ---

    public Map<String, String> getItemState(String itemId) {
        String itemKey = getNamespacedKey("item:" + itemId);
        return redis.<String, String>opsForHash().entries(itemKey);
    }

    /**
     * Efficiently fetches ALL active items for the initial graph load.
     */
    public List<Map<String, Object>> getAllActiveItems() {
        String setKey = getNamespacedKey("sys:active_items");

        Set<String> activeIds = redis.opsForSet().members(setKey);
        if (activeIds == null || activeIds.isEmpty())
            return Collections.emptyList();

        List<Map<String, Object>> result = new ArrayList<>();

        for (String id : activeIds) {
            // Must namespace the individual item lookup too
            String itemKey = getNamespacedKey("item:" + id);

            Map<String, String> hash = redis.<String, String>opsForHash().entries(itemKey);
            if (!hash.isEmpty()) {
                Map<String, Object> itemData = new HashMap<>();
                itemData.put("id", id);
                itemData.put("edgeId", hash.get("e"));

                String timeStr = hash.get("t");
                if (timeStr != null) {
                    itemData.put("entryTimestamp", Long.parseLong(timeStr));
                }

                itemData.put("destinationId", hash.get("d"));
                if (hash.containsKey("n")) {
                    itemData.put("name", hash.get("n"));
                }

                result.add(itemData);
            }
        }
        return result;
    }

    /**
     * Returns the count of currently active items in the system.
     */
    public long countActiveItems() {
        String setKey = getNamespacedKey("sys:active_items");
        Long size = redis.opsForSet().size(setKey);
        return size != null ? size : 0;
    }

    // --- PATH CACHING ---

    public void cachePath(String sourceId, String targetId, List<String> path) {
        // Paths must also be isolated per simulation (topology might differ)
        String baseKey = "path_cache:" + sourceId + ":" + targetId;
        String key = getNamespacedKey(baseKey);

        try {
            String jsonPath = objectMapper.writeValueAsString(path);
            redis.opsForValue().set(key, jsonPath, PATH_TTL_MINUTES, TimeUnit.MINUTES);
        } catch (Exception e) {
            logger.error("Failed to serialize path for cache", e);
        }
    }

    public List<String> getCachedPath(String sourceId, String targetId) {
        String baseKey = "path_cache:" + sourceId + ":" + targetId;
        String key = getNamespacedKey(baseKey);

        String jsonPath = redis.opsForValue().get(key);

        if (jsonPath != null) {
            try {
                return objectMapper.readValue(jsonPath, new TypeReference<List<String>>() {
                });
            } catch (Exception e) {
                logger.error("Failed to deserialize cached path", e);
            }
        }
        return null;
    }

    // --- CLEANUP HELPER ---

    /**
     * Deletes all Redis keys associated with a specific simulation.
     * Should be called when a simulation is destroyed.
     */
    public void cleanupSimulationData(String simulationId) {
        if (simulationId == null)
            return;

        // Pattern: sim:{id}:*
        String prefix = "sim:" + simulationId + ":*";

        // Note: keys() is blocking, but acceptable for specific simulation cleanup
        // where keyset is relatively small compared to global keyspace.
        Set<String> keys = redis.keys(prefix);

        if (keys != null && !keys.isEmpty()) {
            logger.info("Cleaning up {} Redis keys for simulation {}", keys.size(), simulationId);
            redis.delete(keys);
        }
    }
}