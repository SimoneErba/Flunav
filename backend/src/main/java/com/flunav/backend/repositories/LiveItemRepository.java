package com.flunav.backend.repositories;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flunav.backend.context.DatabaseContextHolder;
import com.flunav.backend.models.RedisLiveItem;

import flunav.types.PositionType;
import flunav.types.RoutingStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
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
    /**
     * Routes every hot-state key through the active simulation context.
     * This keeps live Redis data and simulation Redis data isolated while allowing
     * service code to use the same repository methods in both modes.
     */
    private String getNamespacedKey(String baseKey) {
        String simId = DatabaseContextHolder.getSimulationId();
        return (simId != null) ? "sim:" + simId + ":" + baseKey : baseKey;
    }

    // --- WRITE OPERATIONS ---

    public void saveItemState(String itemId, String positionId, PositionType type, Instant entryTime,
            double accumulatedDistance, String name, List<String> destinations, String selectedExitId,
            List<String> path) {
        saveItemState(itemId, positionId, type, entryTime, accumulatedDistance, name, destinations, selectedExitId,
                inferRoutingStatus(selectedExitId), entryTime, path);
    }

    /**
     * Stores a complete item hot-state snapshot and indexes it as active.
     * The active set is maintained with the hash so graph reads can bulk-load only
     * items that currently have live or simulation position state.
     */
    public void saveItemState(String itemId, String positionId, PositionType type, Instant entryTime,
            double accumulatedDistance, String name, List<String> destinations, String selectedExitId,
            RoutingStatus routingStatus, Instant routingStatusUpdatedAt, List<String> path) {

        // Create the object
        RedisLiveItem item = RedisLiveItem.builder()
                .id(itemId)
                .positionId(positionId)
                .type(type)
                .entryTime(entryTime)
                .accumulatedDistance(accumulatedDistance)
                .name(name)
                .destinations(destinations)
                .selectedExitId(selectedExitId)
                .routingStatus(routingStatus)
                .routingStatusUpdatedAt(routingStatusUpdatedAt)
                .path(path)
                .build();

        String itemKey = getNamespacedKey("item:" + itemId);
        String setKey = getNamespacedKey("active_items");

        // Convert to Map and Save
        redis.opsForHash().putAll(itemKey, item.toRedisMap(objectMapper));
        redis.opsForSet().add(setKey, itemId);
    }

    /**
     * Updates only the movement fields for a hot item.
     * The offset is stored as accumulated conveyor distance so future projections
     * can resume from a physical checkpoint instead of a rendered percentage.
     */
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

    /**
     * Deletes every active item in the current Redis namespace.
     * This follows the active set so simulation cleanup and live cleanup remove the
     * same item hashes and membership references.
     */
    public void deleteAllItems() {
        String setKey = getNamespacedKey("active_items");

        Set<String> activeIds = redis.opsForSet().members(setKey);

        if (activeIds == null || activeIds.isEmpty()) {
            return;
        }

        List<String> idsToDelete = new ArrayList<>(activeIds);

        deleteItems(idsToDelete);
    }

    /**
     * Advances the item's physics checkpoint without changing its assigned segment.
     * Simulation replay and conveyor speed changes use this to preserve distance
     * already traveled before rescheduling the next internal event.
     */
    public void checkpointPhysics(String itemId, Instant timestamp, double currentDistance) {
        String itemKey = getNamespacedKey("item:" + itemId);
        Map<String, String> updates = new HashMap<>();
        updates.put("t", String.valueOf(timestamp.toEpochMilli()));
        updates.put("ad", String.valueOf(currentDistance));
        redis.opsForHash().putAll(itemKey, updates);
    }

    /**
     * Updates the cached item name in hot state.
     * Durable metadata remains in OrientDB, but active graph reads use this Redis
     * value to avoid stale labels after a rename.
     */
    public void updateName(String itemId, String name) {
        String itemKey = getNamespacedKey("item:" + itemId);
        redis.opsForHash().put(itemKey, "n", name);
    }

    /**
     * Stores destination, exit, and path changes using the legacy inferred status.
     * Older call sites use this when they only know whether an exit was selected.
     */
    public void updateRouting(String itemId, List<String> destinations, String selectedExitId, List<String> path) {
        updateRouting(itemId, destinations, selectedExitId, inferRoutingStatus(selectedExitId), null, path);
    }

    /**
     * Replaces the routing fields while preserving the current movement checkpoint.
     * Null exit or path values deliberately delete the old Redis fields so stale
     * assignments do not survive rerouting or failure states.
     */
    public void updateRouting(String itemId, List<String> destinations, String selectedExitId,
            RoutingStatus routingStatus, Instant routingStatusUpdatedAt, List<String> path) {
        String itemKey = getNamespacedKey("item:" + itemId);
        Map<String, String> updates = new HashMap<>();

        try {
            updates.put("ds", objectMapper.writeValueAsString(destinations != null ? destinations : List.of()));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize destinations for item " + itemId, e);
        }
        if (selectedExitId != null) {
            updates.put("d", selectedExitId);
        }
        if (routingStatus != null) {
            updates.put("rs", routingStatus.name());
        }
        if (routingStatusUpdatedAt != null) {
            updates.put("rst", String.valueOf(routingStatusUpdatedAt.toEpochMilli()));
        }
        if (path != null) {
            try {
                updates.put("p", objectMapper.writeValueAsString(path));
            } catch (Exception e) {
                logger.warn("Failed to serialize path for item {}: {}", itemId, path);
            }
        }

        if (!updates.isEmpty()) {
            redis.opsForHash().putAll(itemKey, updates);
        }
        if (selectedExitId == null) {
            redis.opsForHash().delete(itemKey, "d");
        }
        if (path == null) {
            redis.opsForHash().delete(itemKey, "p");
        }
    }

    /**
     * Replaces only the stored route path for the item.
     * This is used when an externally supplied path is validated separately and the
     * current destination or routing status should remain unchanged.
     */
    public void updatePath(String itemId, List<String> path) {
        String itemKey = getNamespacedKey("item:" + itemId);
        try {
            redis.opsForHash().put(itemKey, "p", objectMapper.writeValueAsString(path));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize path for item " + itemId, e);
        }
    }

    /**
     * Updates only lifecycle routing status without disturbing the current
     * movement checkpoint or assigned route.
     */
    public void updateRoutingStatus(String itemId, RoutingStatus routingStatus, Instant routingStatusUpdatedAt) {
        String itemKey = getNamespacedKey("item:" + itemId);
        Map<String, String> updates = new HashMap<>();
        if (routingStatus != null) {
            updates.put("rs", routingStatus.name());
        }
        if (routingStatusUpdatedAt != null) {
            updates.put("rst", String.valueOf(routingStatusUpdatedAt.toEpochMilli()));
        }
        if (!updates.isEmpty()) {
            redis.opsForHash().putAll(itemKey, updates);
        }
    }

    /**
     * Deletes a batch of active item states from the current namespace.
     * Each item is removed through deleteItem so positional membership sets stay in
     * sync with the item hashes.
     */
    public void deleteItems(List<String> itemIds) {
        if (itemIds == null || itemIds.isEmpty())
            return;
        itemIds.forEach(this::deleteItem);
    }

    /**
     * Deletes one item from the current live or simulation context.
     * The active simulation id is read at call time to avoid caching ThreadLocal
     * state in the repository.
     */
    public void deleteItem(String itemId) {
        deleteItem(itemId, DatabaseContextHolder.getSimulationId());
    }

    /**
     * Deletes an item from its hash, active index, and positional membership set.
     * The explicit simulation id variant is used by cleanup/recovery code that must
     * remove state from a namespace without relying on the current ThreadLocal.
     */
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

    /**
     * Reads one item hash from a specific live or simulation namespace.
     * The explicit simulation id overload lets cleanup and recovery inspect state
     * without depending on the current ThreadLocal context.
     */
    public RedisLiveItem getItemState(String itemId, String simulationId) {
        String itemKey = (simulationId != null) ? "sim:" + simulationId + ":item:" + itemId : "item:" + itemId;
        Map<String, String> hash = redis.<String, String>opsForHash().entries(itemKey);

        // ONE LINE PARSING
        return RedisLiveItem.fromRedisMap(itemId, hash, objectMapper);
    }

    /**
     * Bulk-loads active item hashes through a Redis pipeline.
     * Empty hashes are removed from the active set to repair partial deletes before
     * graph projection or routing decisions consume the active item list.
     */
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

    /**
     * Counts active items in the current Redis namespace.
     * Graph and metric code use this for hot-state totals without scanning hashes.
     */
    public long countActiveItems() {
        return countActiveItems(DatabaseContextHolder.getSimulationId());
    }

    /**
     * Counts active items in an explicit namespace.
     * Simulation analytics and cleanup can query a namespace even when the caller is
     * outside that simulation context.
     */
    public long countActiveItems(String simulationId) {
        String setKey = (simulationId != null) ? "sim:" + simulationId + ":active_items" : "active_items";
        Long size = redis.opsForSet().size(setKey);
        return size != null ? size : 0;
    }

    /**
     * Counts in-flight assignments to a chute that have not physically arrived.
     * Routing capacity uses this projected occupancy so multiple items cannot be
     * assigned into the same future slot.
     */
    public long countItemsAssignedToExit(String exitId, String excludedItemId) {
        if (exitId == null) {
            return 0;
        }

        return getAllActiveItems().stream()
                .filter(item -> item != null)
                .filter(item -> excludedItemId == null || !excludedItemId.equals(item.getId()))
                .filter(item -> exitId.equals(item.getSelectedExitId()))
                .filter(item -> !exitId.equals(item.getPositionId()))
                .count();
    }

    /**
     * Preserves compatibility for hashes written before explicit routing statuses.
     * A selected exit used to imply assignment, while no exit represented unrouted
     * movement on the main path.
     */
    private RoutingStatus inferRoutingStatus(String selectedExitId) {
        return selectedExitId == null ? RoutingStatus.UNROUTED : RoutingStatus.ASSIGNED;
    }

    /**
     * Removes stale live-mode hot state only.
     * Startup and cleanup jobs call the explicit live namespace so simulations are
     * not affected by age-based operational cleanup.
     */
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

    /**
     * Deletes every Redis key owned by one simulation namespace.
     * This broad prefix cleanup is scoped to a simulation id so live data and other
     * simulations keep their independent hot state.
     */
    public void cleanupSimulationData(String simulationId) {
        if (simulationId == null)
            return;
        String prefix = "sim:" + simulationId + ":*";
        List<String> batch = new ArrayList<>();
        long deleted = 0;

        try (Cursor<String> cursor = redis.scan(ScanOptions.scanOptions().match(prefix).count(1000).build())) {
            while (cursor.hasNext()) {
                batch.add(cursor.next());
                if (batch.size() >= 500) {
                    redis.delete(batch);
                    deleted += batch.size();
                    batch.clear();
                }
            }
        }

        if (!batch.isEmpty()) {
            redis.delete(batch);
            deleted += batch.size();
        }

        if (deleted > 0) {
            logger.info("Cleaned up {} Redis keys for simulation {}", deleted, simulationId);
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
