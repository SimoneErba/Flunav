package com.fiumen.backend.repositories;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Repository
public class LiveItemRepository {

    private final StringRedisTemplate redis;

    public LiveItemRepository(StringRedisTemplate redis) {
        this.redis = redis;
    }

    // --- WRITE OPERATIONS ---

    public void saveItemState(String itemId, String edgeId, Instant entryTime, String destId, String name) {
        String key = "item:" + itemId;

        Map<String, String> data = new HashMap<>();
        data.put("e", edgeId);
        data.put("t", String.valueOf(entryTime.toEpochMilli()));

        // Optional Destination
        if (destId != null) {
            data.put("d", destId);
        }

        // Optional Name (e.g. during rehydration if we don't have it yet)
        if (name != null) {
            data.put("n", name);
        }

        // 1. Save the Hash
        redis.opsForHash().putAll(key, data);

        // 2. Add to the "Active Set" index
        redis.opsForSet().add("sys:active_items", itemId);
    }

    public void updatePosition(String itemId, String newEdgeId, Instant entryTime) {
        String key = "item:" + itemId;
        // We only update the specific fields that changed
        redis.opsForHash().put(key, "e", newEdgeId);
        redis.opsForHash().put(key, "t", String.valueOf(entryTime.toEpochMilli()));
    }

    public void updateName(String itemId, String name) {
        String key = "item:" + itemId;
        // We only update the specific fields that changed
        redis.opsForHash().put(key, "n", name);
    }

    public void deleteItem(String itemId) {
        String key = "item:" + itemId;
        redis.delete(key);
        redis.opsForSet().remove("sys:active_items", itemId);
    }

    // --- READ OPERATIONS ---

    public Map<String, String> getItemState(String itemId) {
        // Returns the raw hash map
        return redis.<String, String>opsForHash().entries("item:" + itemId);
    }

    /**
     * Efficiently fetches ALL active items for the initial graph load.
     * Uses Pipelining implicitly via multi-get if we structured it right,
     * but here we iterate the set.
     */
    public List<Map<String, Object>> getAllActiveItems() {
        Set<String> activeIds = redis.opsForSet().members("sys:active_items");
        if (activeIds == null || activeIds.isEmpty())
            return Collections.emptyList();

        List<Map<String, Object>> result = new ArrayList<>();

        // Optimization: In a real high-load scenario, use a Pipeline here.
        // For now, a loop is fine for < 5000 items.
        for (String id : activeIds) {
            Map<String, String> hash = redis.<String, String>opsForHash().entries("item:" + id);
            if (!hash.isEmpty()) {
                Map<String, Object> itemData = new HashMap<>();
                itemData.put("id", id);
                itemData.put("edgeId", hash.get("e"));
                itemData.put("entryTimestamp", Long.parseLong(hash.get("t")));
                itemData.put("destinationId", hash.get("d"));
                result.add(itemData);
            }
        }
        return result;
    }

    /**
     * Returns the count of currently active items in the system.
     */
    public long countActiveItems() {
        Long size = redis.opsForSet().size("sys:active_items");
        return size != null ? size : 0;
    }
}