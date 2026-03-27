package com.flunav.backend.repositories;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flunav.backend.context.DatabaseContextHolder;
import com.flunav.backend.models.RedisLiveItem;

import flunav.types.PositionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Repository
public class LiveItemRepository {
    private static final Logger logger = LoggerFactory.getLogger(LiveItemRepository.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public LiveItemRepository(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    // --- MULTI-TENANCY HELPER ---
    private String getNamespacedKey(String baseKey) {
        String simId = DatabaseContextHolder.getSimulationId();
        return (simId != null) ? "sim:" + simId + ":" + baseKey : baseKey;
    }

    // --- WRITE OPERATIONS ---

    public void saveItemState(String itemId, String positionId, PositionType type, Instant entryTime,
            double accumulatedDistance, String name, String destId, List<String> path) {

        // Create the object
        RedisLiveItem item = RedisLiveItem.builder()
                .id(itemId)
                .positionId(positionId)
                .type(type)
                .entryTime(entryTime)
                .accumulatedDistance(accumulatedDistance)
                .name(name)
                .destinationId(destId)
                .path(path)
                .build();

        String itemKey = getNamespacedKey("item:" + itemId);
        String setKey = getNamespacedKey("active_items");

        // Convert to Map and Save
        redis.opsForHash().putAll(itemKey, item.toRedisMap(objectMapper));
        redis.opsForSet().add(setKey, itemId);
    }

    public void updatePosition(String itemId, String positionId, PositionType type, Instant entryTime,
            double offsetMeters, List<String> path) {

        // We can reuse the builder for partial updates if we want,
        // or just construct the specific fields we want to update.
        RedisLiveItem item = RedisLiveItem.builder()
                .id(itemId)
                .positionId(positionId)
                .type(type)
                .entryTime(entryTime)
                .accumulatedDistance(offsetMeters)
                .path(path)
                .build();

        String itemKey = getNamespacedKey("item:" + itemId);
        String activeSetKey = getNamespacedKey("active_items");

        redis.opsForHash().putAll(itemKey, item.toRedisMap(objectMapper));
        redis.expire(itemKey, Duration.ofHours(1));
        redis.opsForSet().add(activeSetKey, itemId);
    }

    public void deleteAllItems() {
        String setKey = getNamespacedKey("active_items");

        Set<String> activeIds = redis.opsForSet().members(setKey);

        if (activeIds == null || activeIds.isEmpty()) {
            return;
        }

        List<String> idsToDelete = new ArrayList<>(activeIds);

        deleteItems(idsToDelete);
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

    public void deleteItems(List<String> itemIds) {
        if (itemIds == null || itemIds.isEmpty())
            return;
        itemIds.forEach(this::deleteItem);
    }

    public void deleteItem(String itemId) {
        deleteItem(itemId, DatabaseContextHolder.getSimulationId());
    }

    public void deleteItem(String itemId, String simulationId) {
        // 1. Get state to find where the item is
        RedisLiveItem item = getItemState(itemId, simulationId);

        if (item != null && item.getPositionId() != null) {
            if (item.getType() == PositionType.CONVEYOR) {
                // Remove from Conveyor Set
                String convItemsKey = (simulationId != null)
                        ? "sim:" + simulationId + ":conv:" + item.getPositionId() + ":items"
                        : "conv:" + item.getPositionId() + ":items";
                redis.opsForZSet().remove(convItemsKey, itemId);
            } else {
                // Remove from Location ZSet (New Logic)
                // Assumes any type other than CONVEYOR is a Node (Location, Chute, etc.)
                String locItemsKey = (simulationId != null)
                        ? "sim:" + simulationId + ":loc:" + item.getPositionId() + ":items"
                        : "loc:" + item.getPositionId() + ":items";
                redis.opsForZSet().remove(locItemsKey, itemId);
            }
        }

        // 2. Delete the Item Hash and remove from Global Index
        String itemKey = (simulationId != null) ? "sim:" + simulationId + ":item:" + itemId : "item:" + itemId;
        String setKey = (simulationId != null) ? "sim:" + simulationId + ":active_items" : "active_items";

        redis.delete(itemKey);
        redis.opsForSet().remove(setKey, itemId);
    }

    // --- READ OPERATIONS ---

    public RedisLiveItem getItemState(String itemId) {
        return getItemState(itemId, DatabaseContextHolder.getSimulationId());
    }

    public RedisLiveItem getItemState(String itemId, String simulationId) {
        String itemKey = (simulationId != null) ? "sim:" + simulationId + ":item:" + itemId : "item:" + itemId;
        Map<String, String> hash = redis.<String, String>opsForHash().entries(itemKey);

        // ONE LINE PARSING
        return RedisLiveItem.fromRedisMap(itemId, hash, objectMapper);
    }

    public List<RedisLiveItem> getAllActiveItems() {
        String setKey = getNamespacedKey("active_items");
        Set<String> activeIds = redis.opsForSet().members(setKey);

        if (activeIds == null || activeIds.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> idList = new ArrayList<>(activeIds);

        // Pipeline execution
        List<Object> pipelineResults = redis.executePipelined(
                new org.springframework.data.redis.core.SessionCallback<Object>() {
                    @Override
                    public Object execute(org.springframework.data.redis.core.RedisOperations operations) {
                        for (String id : idList) {
                            String itemKey = getNamespacedKey("item:" + id);
                            operations.opsForHash().entries(itemKey);
                        }
                        return null;
                    }
                });

        List<RedisLiveItem> resultList = new ArrayList<>();

        for (int i = 0; i < idList.size(); i++) {
            String itemId = idList.get(i);
            Object rawResponse = pipelineResults.get(i);

            if (rawResponse instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, String> hash = (Map<String, String>) rawResponse;

                // SELF HEALING
                if (hash.isEmpty()) {
                    redis.opsForSet().remove(setKey, itemId);
                    continue;
                }

                // CLEAN PARSING
                RedisLiveItem item = RedisLiveItem.fromRedisMap(itemId, hash, objectMapper);
                if (item != null) {
                    resultList.add(item);
                }
            }
        }

        return resultList;
    }

    public long countActiveItems() {
        String setKey = getNamespacedKey("active_items");
        Long size = redis.opsForSet().size(setKey);
        return size != null ? size : 0;
    }

    public int deleteLiveItemsOlderThan(Instant cutoff) {
        if (cutoff == null) {
            return 0;
        }

        Set<String> activeIds = redis.opsForSet().members("active_items");
        if (activeIds == null || activeIds.isEmpty()) {
            return 0;
        }

        List<String> staleItemIds = new ArrayList<>();
        for (String itemId : activeIds) {
            RedisLiveItem item = getItemState(itemId, null);
            if (item == null) {
                redis.opsForSet().remove("active_items", itemId);
                continue;
            }

            Instant entryTime = item.getEntryTime();
            if (entryTime != null && entryTime.isBefore(cutoff)) {
                staleItemIds.add(itemId);
            }
        }

        staleItemIds.forEach(itemId -> deleteItem(itemId, null));
        return staleItemIds.size();
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

    public void printAllData() {
        String setKey = getNamespacedKey("active_items");
        Set<String> activeIds = redis.opsForSet().members(setKey);

        System.out.println("\n========== REDIS DUMP: LIVE ITEMS ==========");
        if (activeIds == null || activeIds.isEmpty()) {
            System.out.println("(No active items found)");
        } else {
            List<String> sortedIds = new ArrayList<>(activeIds);
            Collections.sort(sortedIds);

            for (String itemId : sortedIds) {
                String itemKey = getNamespacedKey("item:" + itemId);
                Map<Object, Object> data = redis.opsForHash().entries(itemKey);
                logger.debug(" -> Item ID: {} | Data: {}", itemId, data);
            }
        }
        logger.debug("============================================");
    }
}
