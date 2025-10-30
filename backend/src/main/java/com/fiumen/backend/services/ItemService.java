package com.fiumen.backend.services;

import com.fiumen.backend.domain.Item;
import com.fiumen.backend.domain.Location;
import com.fiumen.backend.models.UpdateModel;
import com.fiumen.backend.models.input.ItemInput;
import com.fiumen.backend.utils.OrientDBUtils;
import com.orientechnologies.orient.core.db.ODatabaseSession;
import com.orientechnologies.orient.core.exception.OConcurrentModificationException;
import com.orientechnologies.orient.core.record.ODirection;
import com.orientechnologies.orient.core.record.OEdge;
import com.orientechnologies.orient.core.record.OVertex;
import com.orientechnologies.orient.core.sql.executor.OResult;
import com.orientechnologies.orient.core.sql.executor.OResultSet;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

@Service
public class ItemService {
    private final OrientDBService orientDBService;
    private final UpdateService updateService;
    public ItemService(OrientDBService orientDBService, UpdateService updateService) {
        this.orientDBService = orientDBService;
        this.updateService = updateService;
    }

    public List<Item> getAllItems() {
        List<Item> items = new ArrayList<>();
        try (ODatabaseSession db = orientDBService.getSession()) {
            try (OResultSet rs = db.query("SELECT * FROM Item")) {
                while (rs.hasNext()) {
                    OResult row = rs.next();
                    row.getVertex().ifPresent(vertex -> {
                        Item item = vertexToItem(vertex);
                        items.add(item);
                    });
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Error while fetching items: " + e.getMessage(), e);
        }
    
        return items;
    }
    

    public Item getItemById(String id) {
        try (ODatabaseSession db = orientDBService.getSession()) {
            var itemInDb = OrientDBUtils.loadAndValidateVertexByCustomId(db, id);
            Item item = vertexToItem(itemInDb);
            return item;
        } catch (Exception e) {
            throw new RuntimeException("Error while fetching item with ID " + id + ": " + e.getMessage(), e);
        }
    }

    public Item createItem(ItemInput item) {
        try (ODatabaseSession db = orientDBService.getSession()) {
            if (OrientDBUtils.checkIfAlreadyExists(db, item.getId())) {
                throw new IllegalArgumentException("Item with ID " + item.getId() + " already exists.");
            }
    
            OVertex vertex = db.newVertex("Item");
            vertex.setProperty("customId", item.getId());
            vertex.setProperty("name", item.getName());
            vertex.setProperty("speed", item.getSpeed());
            vertex.setProperty("active", item.getActive());
            vertex.setProperty("properties", item.getProperties());
    
            vertex.save();
            return vertexToItem(vertex);
        } catch (Exception e) {
            throw new RuntimeException(
                    "Error while creating item with ID " + item.getId() + ": " + e.getMessage(), e);
        }
    }
    

    public Item updateItem(UpdateModel model) {
        return  vertexToItem(this.updateService.updateVertex(model));
    }

    public Item fullUpdateItem(Item item) {
        try (ODatabaseSession db = orientDBService.getSession()) {
            OVertex itemVertex = OrientDBUtils.loadAndValidateVertexByCustomId(db, item.getId());
    
            // 1. Update the simple properties of the Item vertex
            itemVertex.setProperty("name", item.getName());
            itemVertex.setProperty("speed", item.getSpeed());
            itemVertex.setProperty("active", item.isActive());
            itemVertex.setProperty("properties", item.getProperties());
    
            // 2. Reconcile the 'HasPosition' edge (the improved logic)
            OEdge positionEdge = reconcilePosition(db, itemVertex, item.getLocation());
    
            // 3. Update the properties on the edge (progress, etc.)
            //    The reconcilePosition method conveniently returns the correct edge to work with.
            if (positionEdge != null && item.getProgressInfo() != null) {
                positionEdge.setProperty("progress", item.getProgressInfo().getProgress());
                positionEdge.setProperty("datetime", item.getProgressInfo().getDatetime());
                positionEdge.save();
            }
            
            // 4. Save the item vertex itself
            itemVertex.save();
            
            // 5. Return the fully persisted domain object
            return vertexToItem(itemVertex);
    
        }
        catch (OConcurrentModificationException oce) {
            throw oce;
        } catch (Exception e) {
            throw new RuntimeException("Error during full update of item with ID " + item.getId(), e);
        }
    }

    /**
     * Ensures the item's 'HasPosition' edge in the database correctly points to the desired location.
     * This method performs a database write (delete or create) only if the item's location has actually changed.
     *
     * @param db The active ODatabaseSession.
     * @param itemVertex The OVertex for the item being updated.
     * @param desiredLocation The Location domain object representing the item's desired position. Can be null.
     * @return The current and correct OEdge representing the item's position, or null if the item should have no position.
     */
    private OEdge reconcilePosition(ODatabaseSession db, OVertex itemVertex, Location desiredLocation) {
        // === Step 1: Get the current state from the database ===
        
        // An item should only have one 'HasPosition' edge, but we query robustly.
        Iterator<OEdge> currentEdges = itemVertex.getEdges(ODirection.OUT, "HasPosition").iterator();
        OEdge currentEdge = currentEdges.hasNext() ? currentEdges.next() : null;
        String currentPositionId = null;

        if (currentEdge != null) {
            OVertex currentTargetVertex = currentEdge.getTo();
            if (currentTargetVertex != null) {
                currentPositionId = currentTargetVertex.getProperty("id");
            }
        }
        
        // === Step 2: Get the desired state from the domain object ===
        String desiredPositionId = (desiredLocation != null) ? desiredLocation.getId() : null;

        // === Step 3: Compare states and execute the correct logic ===

        // Case A: The position has NOT changed. The state is already correct.
        // This handles both cases where the ID is the same, and where both are null.
        if (Objects.equals(currentPositionId, desiredPositionId)) {
            // Do nothing. Return the existing edge so its properties can be updated.
            return currentEdge;
        }

        // From this point on, we know a change is required.

        // Case B: The item had a position, but now it should have none (or is moving).
        // In either case, the old edge must be deleted.
        if (currentEdge != null) {
            currentEdge.delete();
        }

        // Case C: The item needs to be moved to a new location.
        // This handles both moving from null -> new, and from old -> new.
        if (desiredPositionId != null) {
            // Load the vertex for the new location.
            OVertex toLocationVertex = OrientDBUtils.loadAndValidateVertexByCustomId(db, desiredPositionId);
            // Create the new edge and return it.
            return itemVertex.addEdge(toLocationVertex, "HasPosition");
        }

        // If we reach here, it means the desiredPositionId was null and the old edge has been deleted.
        return null;
    }

    public void deleteItem(String id) {
        try (ODatabaseSession db = orientDBService.getSession()) {
            OVertex toLocationVertex = OrientDBUtils.loadAndValidateVertexByCustomId(db, id);
            toLocationVertex.delete();
        } catch (Exception e) {
            throw new RuntimeException("Error while deleting item with ID " + id + ": " + e.getMessage(), e);
        }
    }
    

    private Item vertexToItem(OVertex vertex) {
        if (vertex == null) {
            throw new IllegalArgumentException("Attempted to convert a null vertex to item.");
        }
        return new Item(
            vertex.getProperty("customId"),
            vertex.getProperty("name"),
            vertex.getProperty("speed"),
            vertex.getProperty("active"),
            vertex.getProperty("properties")
        );
    }
    
}
