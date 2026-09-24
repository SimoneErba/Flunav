package com.flunav.backend.services;

import com.flunav.backend.domain.Item;
import com.flunav.backend.exception.DuplicateItemException;
import com.flunav.backend.models.RedisLiveItem;
import com.flunav.backend.models.UpdateModel;
import com.flunav.backend.models.input.ItemInput;
import com.flunav.backend.repositories.LiveItemRepository;
import com.flunav.backend.utils.OrientDBUtils;
import com.orientechnologies.orient.core.db.ODatabaseSession;
import com.orientechnologies.orient.core.exception.OConcurrentModificationException;
import com.orientechnologies.orient.core.record.OVertex;
import com.orientechnologies.orient.core.sql.executor.OResult;
import com.orientechnologies.orient.core.sql.executor.OResultSet;

import flunav.types.PositionType;
import flunav.types.RoutingStatus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ItemService {
    private static final Logger logger = LoggerFactory.getLogger(ItemService.class);

    private final OrientDBService orientDBService;
    private final UpdateService updateService;
    private final LiveItemRepository redisRepository;
    private final TimeService timeService;
    private final TopologyProvider topologyProvider;

    public ItemService(OrientDBService orientDBService,
            UpdateService updateService,
            LiveItemRepository redisRepository,
            TimeService timeService,
            TopologyProvider topologyProvider) {
        this.orientDBService = orientDBService;
        this.updateService = updateService;
        this.redisRepository = redisRepository;
        this.timeService = timeService;
        this.topologyProvider = topologyProvider;
    }

    /**
     * Fetches all items.
     * 1. Gets static metadata from OrientDB.
     * 2. Gets live positions from Redis.
     * 3. Merges them.
     */
    public List<Item> getAllItems() {
        List<Item> items = new ArrayList<>();

        // 1. Fetch Metadata from OrientDB
        try (ODatabaseSession db = orientDBService.getSession()) {
            if (db == null)
                return items;
            try (OResultSet rs = db.query("SELECT * FROM Item")) {
                while (rs.hasNext()) {
                    OResult row = rs.next();
                    row.getVertex().ifPresent(vertex -> {
                        items.add(vertexToItem(vertex));
                    });
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Error while fetching items from OrientDB: " + e.getMessage(), e);
        }

        // 2. Fetch Live State from Redis (Bulk)
        List<RedisLiveItem> liveStates = redisRepository.getAllActiveItems();

        // Convert List to Map for O(1) lookup
        Map<String, RedisLiveItem> liveStateMap = liveStates.stream()
                .collect(Collectors.toMap(m -> m.getId(), m -> m));

        // 3. Merge
        for (Item item : items) {
            RedisLiveItem state = liveStateMap.get(item.getId());
            if (state != null) {
                String posId = state.getPositionId();
                PositionType type = state.getType();
                Instant time = state.getEntryTime();
                Double dist = state.getAccumulatedDistance();
                item.updatePosition(posId, type, time, dist);
                item.setDestinations(state.getDestinations());
                item.setSelectedExitId(state.getSelectedExitId());
                item.setRoutingStatus(effectiveRoutingStatus(state.getRoutingStatus(), state.getSelectedExitId()));
                item.setRoutingStatusUpdatedAt(state.getRoutingStatusUpdatedAt());
                item.setPath(state.getPath());
            }
        }

        return items;
    }

    /**
     * Loads durable item metadata and overlays the current hot position state.
     * Keeping OrientDB and Redis reads separate preserves the boundary between
     * replayable item identity and transient movement state.
     */
    public Item getItemById(String id) {
        // 1. Fetch Metadata
        Item item = null;
        try (ODatabaseSession db = orientDBService.getSession()) {
            if (db != null) {
                var itemInDb = OrientDBUtils.loadAndValidateVertexByCustomId(db, id);
                item = vertexToItem(itemInDb);
            }
        } catch (Exception e) {
            logger.error("Could not fetch item {} from OrientDB", id);
            return null;
        }

        // 2. Fetch Live State
        RedisLiveItem redisState = redisRepository.getItemState(id);

        // 3. Merge
        if (redisState != null) {
            var type = redisState.getType();
            Instant time = redisState.getEntryTime();
            Double dist = redisState.getAccumulatedDistance();
            item.updatePosition(redisState.getPositionId(), type, time, dist);

            item.setDestinations(redisState.getDestinations());
            item.setSelectedExitId(redisState.getSelectedExitId());
            item.setRoutingStatus(effectiveRoutingStatus(redisState.getRoutingStatus(), redisState.getSelectedExitId()));
            item.setRoutingStatusUpdatedAt(redisState.getRoutingStatusUpdatedAt());
            item.setPath(redisState.getPath());
        }

        return item;
    }

    /**
     * Creates the durable item record and its initial hot state together.
     * OrientDB owns metadata, while Redis receives the movement checkpoint needed
     * for live projection and future replay-derived graph reads.
     */
    public void createItem(ItemInput itemInput) {
        try {
            orientDBService.withTransaction(db -> {

                if (db != null && OrientDBUtils.checkIfAlreadyExists(db, itemInput.getId())) {
                    throw new DuplicateItemException("Item with ID " + itemInput.getId() + " already exists.");
                }

                // 1. Create Master Record in OrientDB
                OVertex itemVertex = null;
                if (db != null) {
                    itemVertex = db.newVertex("Item");
                    if (itemVertex != null) {
                        itemVertex.setProperty("customId", itemInput.getId());
                        itemVertex.setProperty("name", itemInput.getName());
                        itemVertex.setProperty("active", itemInput.getActive());
                        itemVertex.setProperty("priority", itemInput.getPriority());
                        itemVertex.setProperty("properties", itemInput.getProperties());

                        itemVertex.save();
                    }
                }

                // TODO: allow for insertion in edge
                Instant entryTime = itemInput.getTimestamp() != null ? itemInput.getTimestamp() : timeService.now();
                PositionType posType = itemInput.getPositionType() != null ? itemInput.getPositionType()
                        : PositionType.LOCATION;
                double initialProgress = itemInput.getProgress() != null && posType == PositionType.CONVEYOR
                        ? Math.min(100.0, Math.max(0.0, itemInput.getProgress()))
                        : 0.0;

                redisRepository.saveItemState(
                        itemInput.getId(),
                        itemInput.getLocationId(),
                        posType,
                        entryTime,
                        initialProgress,
                        itemInput.getName(),
                        itemInput.getDestinations(),
                        itemInput.getSelectedExitId(),
                        itemInput.getRoutingStatus(),
                        itemInput.getRoutingStatusUpdatedAt() != null ? itemInput.getRoutingStatusUpdatedAt()
                                : entryTime,
                        itemInput.getPath(),
                        entryTime);
                if (!Boolean.TRUE.equals(itemInput.getActive())) {
                    redisRepository.setMovementPaused(itemInput.getId(), true);
                }
            });
        } catch (DuplicateItemException e) {
            // We know exactly what this is, so just re-throw it for the processor to
            // handle.
            throw e;
        } catch (Exception e) {
            // It's good practice to re-throw with context
            throw new RuntimeException("Error creating item " + itemInput.getId(), e);
        }
    }

    /**
     * High-frequency update method for the Event Processor.
     * Position events and Redis both represent conveyor progress as a percentage.
     */
    public void updateItemPosition(String itemId, String positionId, PositionType type, Instant timestamp,
            Double progress, List<String> path) {
        double progressPercent = type == PositionType.CONVEYOR && progress != null
                ? Math.min(100.0, Math.max(0.0, progress))
                : 0.0;
        redisRepository.updatePosition(itemId, positionId, type, timestamp, progressPercent, path);
    }

    /**
     * Updates item routing with the legacy inferred routing status.
     * Callers that only know the selected exit use this while newer decision paths
     * pass the explicit status and timestamp.
     */
    public void updateItemRouting(String itemId, List<String> destinations, String selectedExitId, List<String> path) {
        redisRepository.updateRouting(itemId, destinations, selectedExitId, path);
    }

    /**
     * Persists the current routing assignment in Redis hot state.
     * Routing status lives with position state because capacity and retry decisions
     * depend on the latest assignment rather than historical metadata alone.
     */
    public void updateItemRouting(String itemId, List<String> destinations, String selectedExitId,
            RoutingStatus routingStatus, Instant routingStatusUpdatedAt, List<String> path) {
        redisRepository.updateRouting(itemId, destinations, selectedExitId, routingStatus, routingStatusUpdatedAt, path);
    }

    /**
     * Replaces an item's stored path after separate path validation.
     * Destination and routing status are left untouched so manual path updates do
     * not accidentally change capacity or completion state.
     */
    public void updateItemPath(String itemId, List<String> path) {
        redisRepository.updatePath(itemId, path);
    }

    /**
     * Records a lifecycle-only routing status transition in Redis hot state.
     * Arrival completion uses this without rewriting destination or path fields
     * that routing history and UI inspection still need.
     */
    public void updateItemRoutingStatus(String itemId, RoutingStatus routingStatus, Instant routingStatusUpdatedAt) {
        redisRepository.updateRoutingStatus(itemId, routingStatus, routingStatusUpdatedAt);
    }

    /**
     * Backfills routing status for Redis hashes written before the status field.
     * Selected exits imply assigned routing, and missing exits imply unrouted flow.
     */
    private RoutingStatus effectiveRoutingStatus(RoutingStatus status, String selectedExitId) {
        if (status != null) {
            return status;
        }
        return selectedExitId == null ? RoutingStatus.UNROUTED : RoutingStatus.ASSIGNED;
    }

    /**
     * Validates that an externally supplied path is a directed location path.
     * The method rejects nonexistent or disconnected locations before Redis stores a
     * path that GraphService and movement scheduling would later try to follow.
     */
    public List<String> validatePath(List<String> path) {
        if (path == null) {
            throw new IllegalArgumentException("Path is required.");
        }

        List<String> validatedPath = List.copyOf(path);
        for (String locationId : validatedPath) {
            if (locationId == null || locationId.isBlank()) {
                throw new IllegalArgumentException("Path location IDs must be nonblank.");
            }
            if (topologyProvider.getLocationById(locationId) == null) {
                throw new IllegalArgumentException("Path location does not exist: " + locationId);
            }
        }

        for (int index = 0; index < validatedPath.size() - 1; index++) {
            String sourceId = validatedPath.get(index);
            String targetId = validatedPath.get(index + 1);
            boolean connected = topologyProvider.getOutgoingConveyors(sourceId).stream()
                    .anyMatch(conveyor -> targetId.equals(conveyor.getTargetLocationId()));
            if (!connected) {
                throw new IllegalArgumentException(
                        "Path locations are not connected by a directed conveyor: " + sourceId + " -> " + targetId);
            }
        }

        return validatedPath;
    }

    /**
     * Applies a metadata patch to the durable item record.
     * Name changes are mirrored into Redis so active graph labels stay current
     * without waiting for the next full item reload.
     */
    public Item updateItem(UpdateModel model) {
        // Standard property update (OrientDB)
        Item updatedItem = vertexToLocation(this.updateService.updateVertex(model));

        // If name changed, update Redis cache
        if (model.getProperties().containsKey("name")) {
            String newName = (String) model.getProperties().get("name");
            redisRepository.updateName(model.getId(), newName);
        }

        return updatedItem;
    }

    /**
     * Applies a full item update across durable metadata and hot position state.
     * This is reserved for full replacements because most movement updates should
     * touch Redis only to avoid unnecessary OrientDB churn.
     */
    public Item fullUpdateItem(Item item) {
        try (ODatabaseSession db = orientDBService.getSession()) {
            OVertex itemVertex = OrientDBUtils.loadAndValidateVertexByCustomId(db, item.getId());

            // 1. Update OrientDB
            if (itemVertex != null) {
                itemVertex.setProperty("name", item.getName());
                itemVertex.setProperty("active", item.isActive());
                itemVertex.setProperty("priority", item.getPriority());
                itemVertex.setProperty("properties", item.getProperties());

                itemVertex.save();
            }

            // 2. Update Redis (Live State)
            if (item.getPositionId() != null) {
                redisRepository.updatePosition(
                        item.getId(),
                        item.getPositionId(),
                        item.getPositionType(),
                        item.getEntryTimestamp() != null ? item.getEntryTimestamp() : timeService.now(),
                        item.getCurrentProgress() != null ? item.getCurrentProgress() : 0.0,
                        item.getPath());
            }

            return vertexToItem(itemVertex);

        } catch (OConcurrentModificationException | java.util.NoSuchElementException oce) {
            throw oce;
        } catch (Exception e) {
            logger.info(e.toString());
            throw new RuntimeException("Error during full update of item " + item.getId(), e);
        }
    }

    /**
     * Removes an item from both Redis hot state and OrientDB metadata.
     * Redis is cleared first so live graph reads stop projecting the item even if
     * the durable delete fails and the caller has to retry.
     */
    public void deleteItem(String id) {
        redisRepository.deleteItem(id);

        try (ODatabaseSession db = orientDBService.getSession()) {
            if (db == null)
                return;
            OVertex itemVertex = OrientDBUtils.loadAndValidateVertexByCustomId(db, id);
            if (itemVertex != null) {
                itemVertex.delete();
            }
        } catch (Exception e) {
            throw new RuntimeException("Error deleting item " + id, e);
        }
    }

    /**
     * Converts the durable OrientDB vertex into the domain item metadata object.
     * Hot movement fields are merged separately from Redis by read methods.
     */
    private Item vertexToItem(OVertex vertex) {
        if (vertex == null) {
            return null;
        }

        return new Item(
                vertex.getProperty("customId"),
                vertex.getProperty("name"),
                vertex.getProperty("active"),
                vertex.getProperty("priority"),
                vertex.getProperty("properties"));
    }

    /**
     * Keeps the update-service conversion path aligned with item metadata mapping.
     * The method delegates to vertexToItem because items and locations share the
     * generic update service return type.
     */
    private Item vertexToLocation(OVertex vertex) {
        return vertexToItem(vertex);
    }
}
