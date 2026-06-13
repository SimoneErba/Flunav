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
                item.setPath(state.getPath());
            }
        }

        return items;
    }

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
            item.setPath(redisState.getPath());
        }

        return item;
    }

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
                        itemVertex.setProperty("properties", itemInput.getProperties());

                        itemVertex.save();
                    }
                }

                // TODO: allow for insertion in edge
                Instant entryTime = itemInput.getTimestamp() != null ? itemInput.getTimestamp() : timeService.now();
                PositionType posType = itemInput.getPositionType() != null ? itemInput.getPositionType()
                        : PositionType.LOCATION;
                double initialDistance = 0.0;
                if (itemInput.getProgress() != null && posType == PositionType.CONVEYOR) {
                    var conveyor = topologyProvider.getConveyorById(itemInput.getLocationId());
                    if (conveyor != null) {
                        initialDistance = conveyor.getLength() * (itemInput.getProgress() / 100.0);
                    }
                }

                redisRepository.saveItemState(
                        itemInput.getId(),
                        itemInput.getLocationId(),
                        posType,
                        entryTime,
                        initialDistance,
                        itemInput.getName(),
                        itemInput.getDestinations(),
                        itemInput.getSelectedExitId(),
                        itemInput.getPath());
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
     * Only touches Redis.
     */
    public void updateItemPosition(String itemId, String positionId, PositionType type, Instant timestamp,
            Double offset, List<String> path) {
        redisRepository.updatePosition(itemId, positionId, type, timestamp, offset, path);
    }

    public void updateItemRouting(String itemId, List<String> destinations, String selectedExitId, List<String> path) {
        redisRepository.updateRouting(itemId, destinations, selectedExitId, path);
    }

    public void updateItemPath(String itemId, List<String> path) {
        redisRepository.updatePath(itemId, path);
    }

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

    public Item fullUpdateItem(Item item) {
        try (ODatabaseSession db = orientDBService.getSession()) {
            OVertex itemVertex = OrientDBUtils.loadAndValidateVertexByCustomId(db, item.getId());

            // 1. Update OrientDB
            if (itemVertex != null) {
                itemVertex.setProperty("name", item.getName());
                itemVertex.setProperty("active", item.isActive());
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

    private Item vertexToItem(OVertex vertex) {
        if (vertex == null) {
            return null;
        }

        return new Item(
                vertex.getProperty("customId"),
                vertex.getProperty("name"),
                vertex.getProperty("active"),
                vertex.getProperty("properties"));
    }

    private Item vertexToLocation(OVertex vertex) {
        return vertexToItem(vertex);
    }
}
