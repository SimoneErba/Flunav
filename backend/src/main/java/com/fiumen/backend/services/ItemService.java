package com.fiumen.backend.services;

import com.fiumen.backend.domain.Item;
import com.fiumen.backend.models.UpdateModel;
import com.fiumen.backend.models.input.ItemInput;
import com.fiumen.backend.repositories.LiveItemRepository;
import com.fiumen.backend.utils.OrientDBUtils;
import com.orientechnologies.orient.core.db.ODatabaseSession;
import com.orientechnologies.orient.core.exception.OConcurrentModificationException;
import com.orientechnologies.orient.core.record.OVertex;
import com.orientechnologies.orient.core.sql.executor.OResult;
import com.orientechnologies.orient.core.sql.executor.OResultSet;
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
                item.setCurrentEdgeId((String) state.get("edgeId"));
                item.setDestinationId((String) state.get("destinationId"));

                Object ts = state.get("entryTimestamp");
                if (ts instanceof Long) {
                    item.setEntryTimestamp(Instant.ofEpochMilli((Long) ts));
                }
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
            item.setCurrentEdgeId(redisState.get("e"));
            item.setDestinationId(redisState.get("d"));
            String tsStr = redisState.get("t");
            if (tsStr != null) {
                item.setEntryTimestamp(Instant.ofEpochMilli(Long.parseLong(tsStr)));
            }
        }

        return item;
    }

    public Item createItem(ItemInput itemInput) {
        try (ODatabaseSession db = orientDBService.getSession()) {
            db.begin();

            if (OrientDBUtils.checkIfAlreadyExists(db, itemInput.getId())) {
                throw new IllegalArgumentException("Item with ID " + itemInput.getId() + " already exists.");
            }

            // 1. Create Master Record in OrientDB
            OVertex itemVertex = db.newVertex("Item");
            itemVertex.setProperty("customId", itemInput.getId());
            itemVertex.setProperty("name", itemInput.getName());
            itemVertex.setProperty("active", itemInput.getActive());
            itemVertex.setProperty("properties", itemInput.getProperties());
            // Note: We do NOT store position in OrientDB anymore.

            itemVertex.save();
            db.commit();

            // 2. Create Live State in Redis
            redisRepository.saveItemState(
                    itemInput.getId(),
                    itemInput.getLocationId(),
                    Instant.now(),
                    null,
                    itemInput.getName());

            // Return the merged object
            Item createdItem = vertexToItem(itemVertex);
            createdItem.setCurrentEdgeId(itemInput.getLocationId());
            createdItem.setEntryTimestamp(Instant.now());
            return createdItem;

        } catch (Exception e) {
            throw new RuntimeException("Error creating item " + itemInput.getId(), e);
        }
    }

    /**
     * High-frequency update method for the Event Processor.
     * Only touches Redis.
     */
    public void updateItemPosition(String itemId, String edgeId, Instant timestamp) {
        redisRepository.updatePosition(itemId, edgeId, timestamp);
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
            if (item.getCurrentEdgeId() != null) {
                redisRepository.updatePosition(
                        item.getId(),
                        item.getCurrentEdgeId(),
                        item.getEntryTimestamp() != null ? item.getEntryTimestamp() : Instant.now());
            }

            return vertexToItem(itemVertex);

        } catch (OConcurrentModificationException oce) {
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