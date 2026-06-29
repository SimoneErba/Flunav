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
import org.springframework.lang.NonNull;

@Repository
public class LiveConveyorRepository {
    private static final long DEFAULT_TTL_HOURS = 1;
    private static final Logger logger = LoggerFactory.getLogger(LiveConveyorRepository.class);

    private final StringRedisTemplate redis;

    public LiveConveyorRepository(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * Adds an item to a conveyor's ordered hot-state queue.
     * Entry timestamp is the Redis score, so movement and accumulation logic can
     * process the leading items before the following items.
     */
    public void addItemToConveyor(String conveyorId, @NonNull String itemId, Instant timestamp) {
        String key = getNamespacedKey(conveyorId + ":items");
        // Score = Timestamp. Lower score = Entered earlier = Further ahead on belt.
        redis.opsForZSet().add(key, itemId, timestamp.toEpochMilli());
        redis.expire(key, Duration.ofHours(DEFAULT_TTL_HOURS));
    }

    /**
     * Removes one item from conveyor membership.
     * Movement processing calls this when an item leaves a segment so accumulation
     * and recovery no longer treat it as in flight on that conveyor.
     */
    public void removeItemFromConveyor(String conveyorId, String itemId) {
        String key = getNamespacedKey(conveyorId + ":items");
        redis.opsForZSet().remove(key, itemId);
    }

    /**
     * Returns items ordered from conveyor exit back toward the entrance.
     * Accumulation recalculation depends on this order so each item can account for
     * the item physically ahead of it.
     */
    public Set<String> getItemsOrderedByDistance(String conveyorId) {
        String key = getNamespacedKey(conveyorId + ":items");
        // Range 0 to -1 returns all items sorted by score (Oldest/Furthest first)
        return redis.opsForZSet().range(key, 0, -1);
    }

    /**
     * Returns the item currently closest to the conveyor exit.
     * This is a Redis hot-state lookup used by movement logic, not a durable
     * topology query.
     */
    public String getHeadItem(String conveyorId) {
        String key = getNamespacedKey(conveyorId + ":items");
        Set<String> items = redis.opsForZSet().range(key, 0, 0);
        return (items != null && !items.isEmpty()) ? items.iterator().next() : null;
    }

    // --- TAIL & CHUTE LOGIC (Unchanged) ---

    /**
     * Records the furthest blocked tail position on a conveyor.
     * Accumulation logic uses this value to stop following items at a safe distance
     * when the next segment or chute cannot accept them.
     */
    public void updateTailPosition(String conveyorId, double tailMeters) {
        String key = getNamespacedKey(conveyorId + ":tail");
        redis.opsForValue().set(key, String.valueOf(tailMeters));
    }

    /**
     * Reads the current blocked tail position for a conveyor.
     * A missing value means no downstream blockage has reserved space on the
     * segment.
     */
    public Double getTailPosition(String conveyorId) {
        String key = getNamespacedKey(conveyorId + ":tail");
        String val = redis.opsForValue().get(key);
        return val != null ? Double.parseDouble(val) : null;
    }

    /**
     * Increments the legacy chute occupancy counter.
     * Current routing primarily uses location ZSET occupancy, but this method is
     * retained for older callers that still maintain the counter key.
     */
    public void incrementChuteOccupancy(String chuteId) {
        String key = getNamespacedKey("chute:" + chuteId + ":occupancy");
        redis.opsForValue().increment(key);
    }

    /**
     * Decrements the legacy chute occupancy counter.
     * The counter is namespaced with conveyor hot state so simulations stay
     * isolated from live mode.
     */
    public void decrementChuteOccupancy(String chuteId) {
        String key = getNamespacedKey("chute:" + chuteId + ":occupancy");
        redis.opsForValue().decrement(key);
    }

    /**
     * Clears the legacy chute occupancy counter for a chute.
     * Cleanup and tests use this to remove derived hot state without touching
     * durable location metadata.
     */
    public void clearChuteOccupancy(String chuteId) {
        String key = getNamespacedKey("chute:" + chuteId + ":occupancy");
        redis.delete(key);
    }

    /**
     * Reads the legacy chute occupancy counter.
     * Missing Redis values are interpreted as zero so callers do not need separate
     * existence checks.
     */
    public Integer getChuteOccupancy(String chuteId) {
        String key = getNamespacedKey("chute:" + chuteId + ":occupancy");
        String val = redis.opsForValue().get(key);
        return val != null ? Integer.parseInt(val) : 0;
    }

    // --- CLEANUP ---

    /**
     * Removes all hot conveyor state for a conveyor.
     * Topology deletion must clear both ordered membership and tail state so later
     * conveyor ids do not inherit old accumulation data.
     */
    public void deleteConveyor(String conveyorId) {
        String itemsKey = getNamespacedKey(conveyorId + ":items");
        String tailKey = getNamespacedKey(conveyorId + ":tail");
        redis.delete(itemsKey);
        redis.delete(tailKey);
    }

    /**
     * Removes conveyor hot state for one simulation namespace.
     * Cleanup is explicitly keyed by simulation id because the active ThreadLocal
     * may already have been cleared by the caller.
     */
    public void cleanupSimulationData(String simulationId) {
        if (simulationId == null)
            return;
        String pattern = "sim:" + simulationId + ":conv:*";
        Set<String> keys = redis.keys(pattern);
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    /**
     * Builds Redis keys from the active simulation context.
     * Conveyor membership and tail state must use the same namespace as item hot
     * state or movement recovery will mix live and simulation queues.
     */
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
