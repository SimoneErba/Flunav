package com.flunav.backend.repositories;

import com.flunav.backend.context.DatabaseContextHolder;
import flunav.types.PositionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;
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

    private String getNamespacedKey(String baseKey) {
        String simId = DatabaseContextHolder.getSimulationId();
        logger.info("Accessing Redis Key. SimID: {}, BaseKey: {}", simId, baseKey);
        if (simId != null) {
            return "sim:" + simId + ":" + baseKey;
        }
        return baseKey;
    }

    // --- WRITE OPERATIONS ---

    /**
     * Saves the initial state of an item.
     */
    public void saveItemState(String itemId, String positionId, PositionType type, Instant entryTime, String destId,
            String name) {
        String itemKey = getNamespacedKey("item:" + itemId);
        String setKey = getNamespacedKey("active_items");

        Map<String, String> data = new HashMap<>();
        data.put("e", positionId); // 'e' now stores positionId (Location or Conveyor ID)
        data.put("ty", type != null ? type.name() : null); // Store Enum name ("LOCATION" or "CONVEYOR")
        data.put("t", String.valueOf(entryTime.toEpochMilli()));
        data.put("ad", "0.0");

        if (destId != null)
            data.put("d", destId);
        if (name != null)
            data.put("n", name);

        redis.opsForHash().putAll(itemKey, data);
        redis.opsForSet().add(setKey, itemId);
    }

    /**
     * Updates position with support for mid-conveyor injection.
     */
    public void updatePosition(String itemId, String positionId, PositionType type, Instant entryTime,
            double offsetMeters, List<String> path) {

        // 1. Get Keys (Namespaced for Simulation support)
        String itemKey = getNamespacedKey("item:" + itemId);
        String activeSetKey = getNamespacedKey("active_items");

        Map<String, String> updates = new HashMap<>();
        updates.put("e", positionId);
        updates.put("ty", type.name());
        updates.put("t", String.valueOf(entryTime.toEpochMilli()));
        updates.put("ad", String.valueOf(offsetMeters));

        if (path != null) {
            try {
                updates.put("p", objectMapper.writeValueAsString(path));
            } catch (JsonProcessingException e) {
                logger.warn("Path invalid: {}", path);
            }
        }

        // If it exists, it updates. If not, it creates
        redis.opsForHash().putAll(itemKey, updates);

        // 3. Refresh Expiration (Keep it alive)
        redis.expire(itemKey, Duration.ofHours(1));

        redis.opsForSet().add(activeSetKey, itemId);
    }

    public void deleteItems(List<String> itemIds) {
        if (itemIds == null || itemIds.isEmpty())
            return;

        String activeSetKey = getNamespacedKey("active_items");
        List<String> keys = itemIds.stream()
                .map(id -> getNamespacedKey("item:" + id))
                .toList();

        redis.delete(keys);

        redis.opsForSet().remove(activeSetKey, itemIds.toArray());
    }

    public void checkpointPhysics(String itemId, Instant timestamp, double currentDistance) {
        String itemKey = getNamespacedKey("item:" + itemId);
        Map<String, String> updates = new HashMap<>();
        updates.put("t", String.valueOf(timestamp.toEpochMilli()));
        updates.put("ad", String.valueOf(currentDistance));
        redis.opsForHash().putAll(itemKey, updates);
    }

    public void updateName(String itemId, String name) {
        String itemKey = getNamespacedKey("item:" + itemId);
        redis.opsForHash().put(itemKey, "n", name);
    }

    public void deleteItem(String itemId) {
        String itemKey = getNamespacedKey("item:" + itemId);
        String setKey = getNamespacedKey("active_items");

        redis.delete(itemKey);
        redis.opsForSet().remove(setKey, itemId);
    }

    // --- READ OPERATIONS ---

    public Map<String, String> getItemState(String itemId) {
        String itemKey = getNamespacedKey("item:" + itemId);
        return redis.<String, String>opsForHash().entries(itemKey);
    }

    /**
     * Retrieves all active items efficiently using Redis Pipelining.
     * This reduces network round-trips from N to 1.
     */
    public List<Map<String, Object>> getAllActiveItems() {
        // Get the Index (The Set of IDs)
        String setKey = getNamespacedKey("active_items");
        Set<String> activeIds = redis.opsForSet().members(setKey);

        if (activeIds == null || activeIds.isEmpty()) {
            return Collections.emptyList();
        }

        // Convert to List to ensure the order matches the pipeline results
        List<String> idList = new ArrayList<>(activeIds);

        // Fetch all Hashes in ONE Network Call
        // We use SessionCallback to ensure we use the StringRedisTemplate's serializers
        List<Object> pipelineResults = redis
                .executePipelined(new org.springframework.data.redis.core.SessionCallback<Object>() {
                    @Override
                    public Object execute(org.springframework.data.redis.core.RedisOperations operations)
                            throws org.springframework.dao.DataAccessException {
                        for (String id : idList) {
                            String itemKey = getNamespacedKey("item:" + id);
                            // Queue the HGETALL command
                            operations.opsForHash().entries(itemKey);
                        }
                        return null; // Must return null in pipeline
                    }
                });

        // 3. Process results in Memory
        List<Map<String, Object>> resultList = new ArrayList<>();

        for (int i = 0; i < idList.size(); i++) {
            String itemId = idList.get(i);
            Object rawResponse = pipelineResults.get(i);

            // Check if the result is valid
            if (rawResponse instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, String> hash = (Map<String, String>) rawResponse;

                // SELF HEALING
                if (hash.isEmpty()) {
                    redis.opsForSet().remove(setKey, itemId);
                    continue;
                }

                // --- FULL MAPPING ---
                Map<String, Object> itemData = new HashMap<>();
                itemData.put("id", itemId);

                if (hash.containsKey("e")) {
                    itemData.put("positionId", hash.get("e"));
                }

                String typeStr = hash.get("ty");
                if (typeStr != null) {
                    try {
                        itemData.put("positionType", PositionType.valueOf(typeStr));
                    } catch (IllegalArgumentException e) {
                        itemData.put("positionType", PositionType.LOCATION);
                    }
                }

                String timeStr = hash.get("t");
                if (timeStr != null) {
                    try {
                        itemData.put("entryTimestamp", Long.parseLong(timeStr));
                    } catch (NumberFormatException e) {
                        // Ignore or log
                    }
                }

                if (hash.containsKey("d")) {
                    itemData.put("destinationId", hash.get("d"));
                }

                if (hash.containsKey("n")) {
                    itemData.put("name", hash.get("n"));
                }

                String distStr = hash.get("ad");
                if (distStr != null) {
                    try {
                        itemData.put("accumulatedDistance", Double.parseDouble(distStr));
                    } catch (NumberFormatException e) {
                        itemData.put("accumulatedDistance", 0.0);
                    }
                } else {
                    itemData.put("accumulatedDistance", 0.0);
                }

                String pathStr = hash.get("p");
                List<String> pathList = new ArrayList<>();
                if (pathStr != null && !pathStr.isEmpty() && !pathStr.equals("[]")) {
                    try {
                        pathList = objectMapper.readValue(pathStr, new TypeReference<List<String>>() {
                        });
                    } catch (Exception e) {
                        logger.warn("Failed to parse path JSON for item {}: {}", itemId, pathStr);
                    }
                }
                itemData.put("path", pathList);

                // Add mapped item to result
                resultList.add(itemData);
            }
        }

        return resultList;
    }

    public long countActiveItems() {
        String setKey = getNamespacedKey("active_items");
        Long size = redis.opsForSet().size(setKey);
        return size != null ? size : 0;
    }

    // --- PATH CACHING ---

    public void cachePath(String sourceId, String targetId, List<String> path) {
        String baseKey = "path_cache:" + sourceId + ":" + targetId;
        String key = getNamespacedKey(baseKey);

        try {
            String jsonPath = objectMapper.writeValueAsString(path);
            redis.opsForValue().set(key, jsonPath, PATH_TTL_MINUTES, TimeUnit.MINUTES);
        } catch (Exception e) {
            logger.error("Failed to serialize path for cache", e);
        }
    }

    // if we want to cache the paths to avoid re running djikstra. but we need to
    // invalidate this every time a location changes, very messy
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

    public void cleanupSimulationData(String simulationId) {
        if (simulationId == null)
            return;
        String prefix = "sim:" + simulationId + ":*";
        Set<String> keys = redis.keys(prefix);
        if (keys != null && !keys.isEmpty()) {
            logger.info("Cleaning up {} Redis keys for simulation {}", keys.size(), simulationId);
            redis.delete(keys);
        }
    }
}