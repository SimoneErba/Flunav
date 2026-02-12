package com.flunav.backend.repositories;

import com.flunav.backend.context.DatabaseContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

@Repository
public class LiveLocationRepository {
    private static final Logger logger = LoggerFactory.getLogger(LiveLocationRepository.class);
    private static final long DEFAULT_TTL_HOURS = 1;

    private final StringRedisTemplate redis;

    public LiveLocationRepository(StringRedisTemplate redis) {
        this.redis = redis;
    }

    // --- WRITE OPERATIONS ---

    /**
     * Adds an item to a location (Junction, Chute, Station).
     * Uses ZSET with Timestamp as score to maintain FIFO order.
     */
    public void addItemToLocation(String locationId, String itemId) {
        String key = getNamespacedKey(locationId);
        // Score = Current Time (allows us to pop the "oldest" item later)
        double score = Instant.now().toEpochMilli();
        
        redis.opsForZSet().add(key, itemId, score);
        redis.expire(key, Duration.ofHours(DEFAULT_TTL_HOURS));
    }

    public void removeItemFromLocation(String locationId, String itemId) {
        String key = getNamespacedKey(locationId);
        redis.opsForZSet().remove(key, itemId);
    }

    /**
     * Removes and returns the item that has been at the location the longest.
     * Useful for processing queues or chutes.
     */
    public String popOldestItem(String locationId) {
        String key = getNamespacedKey(locationId);
        Set<String> items = redis.opsForZSet().range(key, 0, 0);
        if (items != null && !items.isEmpty()) {
            String itemId = items.iterator().next();
            redis.opsForZSet().remove(key, itemId);
            return itemId;
        }
        return null;
    }

    // --- READ OPERATIONS ---

    public Set<String> getItemsAtLocation(String locationId) {
        String key = getNamespacedKey(locationId);
        // Returns items ordered by arrival time
        return redis.opsForZSet().range(key, 0, -1);
    }

    /**
     * Replaces the manual "Chute Occupancy" counter.
     * This is the source of truth for how many items are at a node.
     */
    public Long getItemCount(String locationId) {
        String key = getNamespacedKey(locationId);
        Long count = redis.opsForZSet().zCard(key);
        return count != null ? count : 0L;
    }

    public boolean isLocationEmpty(String locationId) {
        return getItemCount(locationId) == 0;
    }

    // --- CLEANUP ---

    public void deleteLocation(String locationId) {
        String key = getNamespacedKey(locationId);
        redis.delete(key);
    }

    public void cleanupSimulationData(String simulationId) {
        if (simulationId == null) return;
        String pattern = "sim:" + simulationId + ":loc:*";
        Set<String> keys = redis.keys(pattern);
        if (keys != null && !keys.isEmpty()) {
            logger.info("Cleaning up {} Location keys for simulation {}", keys.size(), simulationId);
            redis.delete(keys);
        }
    }

    // --- HELPER ---

    private String getNamespacedKey(String locationId) {
        String simId = DatabaseContextHolder.getSimulationId();
        if (simId != null) {
            return "sim:" + simId + ":loc:" + locationId + ":items";
        }
        return "loc:" + locationId + ":items";
    }
}