package com.flunav.backend.repositories;

import com.flunav.backend.context.DatabaseContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

@Repository
public class LiveConveyorRepository {
    private static final long DEFAULT_TTL_HOURS = 1;
    private static final Logger logger = LoggerFactory.getLogger(LiveConveyorRepository.class);

    private final StringRedisTemplate redis;

    public LiveConveyorRepository(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * Adds item to conveyor preserving order.
     * 
     * @param timestamp The time the item entered the conveyor (used for sorting).
     */
    public void addItemToConveyor(String conveyorId, String itemId, Instant timestamp) {
        String key = getNamespacedKey(conveyorId + ":items");
        // Score = Timestamp. Lower score = Entered earlier = Further ahead on belt.
        redis.opsForZSet().add(key, itemId, timestamp.toEpochMilli());
        redis.expire(key, Duration.ofHours(DEFAULT_TTL_HOURS));
    }

    public void removeItemFromConveyor(String conveyorId, String itemId) {
        String key = getNamespacedKey(conveyorId + ":items");
        redis.opsForZSet().remove(key, itemId);
    }

    /**
     * Returns items ordered from Furthest (End of belt) to Closest (Start of belt).
     * Essential for calculating collisions/accumulation from the front backwards.
     */
    public Set<String> getItemsOrderedByDistance(String conveyorId) {
        String key = getNamespacedKey(conveyorId + ":items");
        // Range 0 to -1 returns all items sorted by score (Oldest/Furthest first)
        return redis.opsForZSet().range(key, 0, -1);
    }

    /**
     * Returns the item at the very front of the conveyor (closest to exit).
     */
    public String getHeadItem(String conveyorId) {
        String key = getNamespacedKey(conveyorId + ":items");
        Set<String> items = redis.opsForZSet().range(key, 0, 0);
        return (items != null && !items.isEmpty()) ? items.iterator().next() : null;
    }

    // --- TAIL & CHUTE LOGIC (Unchanged) ---

    public void updateTailPosition(String conveyorId, double tailMeters) {
        String key = getNamespacedKey(conveyorId + ":tail");
        redis.opsForValue().set(key, String.valueOf(tailMeters));
    }

    public Double getTailPosition(String conveyorId) {
        String key = getNamespacedKey(conveyorId + ":tail");
        String val = redis.opsForValue().get(key);
        return val != null ? Double.parseDouble(val) : null;
    }

    public void incrementChuteOccupancy(String chuteId) {
        String key = getNamespacedKey("chute:" + chuteId + ":occupancy");
        redis.opsForValue().increment(key);
    }

    public void decrementChuteOccupancy(String chuteId) {
        String key = getNamespacedKey("chute:" + chuteId + ":occupancy");
        redis.opsForValue().decrement(key);
    }

    public void clearChuteOccupancy(String chuteId) {
        String key = getNamespacedKey("chute:" + chuteId + ":occupancy");
        redis.delete(key);
    }

    public Integer getChuteOccupancy(String chuteId) {
        String key = getNamespacedKey("chute:" + chuteId + ":occupancy");
        String val = redis.opsForValue().get(key);
        return val != null ? Integer.parseInt(val) : 0;
    }

    // --- CLEANUP ---

    public void deleteConveyor(String conveyorId) {
        String itemsKey = getNamespacedKey(conveyorId + ":items");
        String tailKey = getNamespacedKey(conveyorId + ":tail");
        redis.delete(itemsKey);
        redis.delete(tailKey);
    }

    public void cleanupSimulationData(String simulationId) {
        if (simulationId == null)
            return;
        String pattern = "sim:" + simulationId + ":conv:*";
        Set<String> keys = redis.keys(pattern);
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    private String getNamespacedKey(String baseKey) {
        String simId = DatabaseContextHolder.getSimulationId();
        if (simId != null) {
            return "sim:" + simId + ":conv:" + baseKey;
        }
        return "conv:" + baseKey;
    }

    public void printAllData() {
        // Construct the search pattern based on whether we are in a simulation or not
        String simId = DatabaseContextHolder.getSimulationId();
        String pattern = (simId != null) ? "sim:" + simId + ":conv:*:items" : "conv:*:items";

        Set<String> keys = redis.keys(pattern);

        logger.debug("\n======== REDIS DUMP: LIVE CONVEYORS ========");
        if (keys == null || keys.isEmpty()) {
            logger.debug("(No active conveyors found)");
        } else {
            List<String> sortedKeys = new ArrayList<>(keys);
            Collections.sort(sortedKeys);

            for (String key : sortedKeys) {
                // Get all items with their scores (timestamps)
                Set<org.springframework.data.redis.core.ZSetOperations.TypedTuple<String>> items = redis.opsForZSet()
                        .rangeWithScores(key, 0, -1);

                // Get tail info if available
                String tailKey = key.replace(":items", ":tail");
                String tailVal = redis.opsForValue().get(tailKey);

                logger.debug("KEY: {} [Tail: {}", key, (tailVal != null ? tailVal : "N/A"));

                if (items != null && !items.isEmpty()) {
                    for (var item : items) {
                        logger.debug("   └─ Item: {} (Entry Time: {})", item.getValue(), item.getScore());
                    }
                } else {
                    logger.debug("   └─ (Empty)");
                }
            }
        }
        logger.debug("============================================");
    }
}