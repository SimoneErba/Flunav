package com.flunav.backend.services;

import com.flunav.backend.domain.Item;
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
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ItemService {
    private static final Logger logger = LoggerFactory.getLogger(ItemService.class);

    private final OrientDBService orientDBService;
    private final UpdateService updateService;
    private final LiveItemRepository redisRepository;

    public ItemService(OrientDBService orientDBService,
            UpdateService updateService,
            LiveItemRepository redisRepository) {
        this.orientDBService = orientDBService;
        this.updateService = updateService;
        this.redisRepository = redisRepository;
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
        List<Map<String, Object>> liveStates = redisRepository.getAllActiveItems();

        // Convert List to Map for O(1) lookup
        Map<String, Map<String, Object>> liveStateMap = liveStates.stream()
                .collect(Collectors.toMap(m -> (String) m.get("id"), m -> m));

        // 3. Merge
        for (Item item : items) {
            Map<String, Object> state = liveStateMap.get(item.getId());
            if (state != null) {
                // Map Position ID
                String posId = (String) state.get("edgeId"); // Repository returns 'edgeId' key for position

                // Map Type
                PositionType type = (PositionType) state.get("positionType"); // Repository returns Enum
                if (type == null)
                    type = PositionType.LOCATION;

                // Map Time
                Instant time = null;
                Object ts = state.get("entryTimestamp");
                if (ts instanceof Long) {
                    time = Instant.ofEpochMilli((Long) ts);
                }

                // Map Distance (Offset)
                Double dist = (Double) state.get("accumulatedDistance");
                if (dist == null)
                    dist = 0.0;

                // Update Item
                item.updatePosition(posId, type, time, dist);

                // Map Destination
                item.setDestinationId((String) state.get("destinationId"));
            }
        }

        return items;
    }

    public Item getItemById(String id) {
        // 1. Fetch Metadata
        Item item;
        try (ODatabaseSession db = orientDBService.getSession()) {
            var itemInDb = OrientDBUtils.loadAndValidateVertexByCustomId(db, id);
            item = vertexToItem(itemInDb);
        } catch (Exception e) {
            throw new RuntimeException("Error while fetching item with ID " + id, e);
        }

        // 2. Fetch Live State
        Map<String, String> redisState = redisRepository.getItemState(id);

        // 3. Merge
        if (!redisState.isEmpty()) {
            // Parse Type
            String typeStr = redisState.get("ty");
            PositionType type = (typeStr != null && !typeStr.isEmpty()) ? PositionType.valueOf(typeStr)
                    : PositionType.LOCATION;

            // Parse Time
            Instant time = null;
            String tsStr = redisState.get("t");
            if (tsStr != null) {
                time = Instant.ofEpochMilli(Long.parseLong(tsStr));
            }

            // Parse Distance
            Double dist = 0.0;
            String distStr = redisState.get("ad");
            if (distStr != null) {
                dist = Double.parseDouble(distStr);
            }

            // Update Item
            item.updatePosition(redisState.get("e"), type, time, dist);

            item.setDestinationId(redisState.get("d"));
        }

        return item;
    }

    public Item createItem(ItemInput itemInput) {
        try (ODatabaseSession db = orientDBService.getSession()) {

            if (OrientDBUtils.checkIfAlreadyExists(db, itemInput.getId())) {
                throw new IllegalArgumentException("Item with ID " + itemInput.getId() + " already exists.");
            }

            // 1. Create Master Record in OrientDB
            OVertex itemVertex = db.newVertex("Item");
            itemVertex.setProperty("customId", itemInput.getId());
            itemVertex.setProperty("name", itemInput.getName());
            itemVertex.setProperty("active", itemInput.getActive());
            itemVertex.setProperty("properties", itemInput.getProperties());

            itemVertex.save();

            // TODO: allow for insertion in edge
            Instant entryTime = itemInput.getTimestamp() != null ? itemInput.getTimestamp() : Instant.now();
            redisRepository.saveItemState(
                    itemInput.getId(),
                    itemInput.getLocationId(),
                    PositionType.LOCATION, // Default
                    entryTime,
                    null,
                    itemInput.getName());

            // Return the merged object
            Item createdItem = vertexToItem(itemVertex);
            createdItem.updatePosition(itemInput.getLocationId(), PositionType.LOCATION, entryTime, 0.0);
            return createdItem;

        } catch (Exception e) {
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

    public Item updateItem(UpdateModel model) {
        // Standard property update (OrientDB)
        Item updatedItem = vertexToItem(this.updateService.updateVertex(model));

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
            itemVertex.setProperty("name", item.getName());
            itemVertex.setProperty("active", item.isActive());
            itemVertex.setProperty("properties", item.getProperties());

            itemVertex.save();

            // 2. Update Redis (Live State)
            if (item.getPositionId() != null) {
                redisRepository.updatePosition(
                        item.getId(),
                        item.getPositionId(),
                        item.getPositionType(),
                        item.getEntryTimestamp() != null ? item.getEntryTimestamp() : Instant.now(),
                        item.getCurrentProgress() != null ? item.getCurrentProgress() : 0.0,
                        item.getPath());
            }

            return vertexToItem(itemVertex);

        } catch (OConcurrentModificationException | java.util.NoSuchElementException oce) {
            throw oce;
        } catch (Exception e) {
            throw new RuntimeException("Error during full update of item " + item.getId(), e);
        }
    }

    public void deleteItem(String id) {
        redisRepository.deleteItem(id);

        try (ODatabaseSession db = orientDBService.getSession()) {
            OVertex itemVertex = OrientDBUtils.loadAndValidateVertexByCustomId(db, id);
            itemVertex.delete();
        } catch (Exception e) {
            throw new RuntimeException("Error deleting item " + id, e);
        }
    }

    private Item vertexToItem(OVertex vertex) {
        if (vertex == null) {
            throw new IllegalArgumentException("Attempted to convert a null vertex to item.");
        }

        return new Item(
                vertex.getProperty("customId"),
                vertex.getProperty("name"),
                vertex.getProperty("active"),
                vertex.getProperty("properties"));
    }
}