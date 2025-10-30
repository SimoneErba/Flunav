package com.fiumen.backend.services;

import com.fiumen.backend.domain.Location;
import com.fiumen.backend.models.UpdateModel;
import com.fiumen.backend.models.input.LocationInput;
import com.fiumen.backend.utils.OrientDBUtils;
import com.orientechnologies.orient.core.db.ODatabaseSession;
import com.orientechnologies.orient.core.exception.OConcurrentModificationException;
import com.orientechnologies.orient.core.record.ODirection;
import com.orientechnologies.orient.core.record.OEdge;
import com.orientechnologies.orient.core.record.OVertex;
import com.orientechnologies.orient.core.sql.executor.OResult;
import com.orientechnologies.orient.core.sql.executor.OResultSet;

import fiumen.types.LocationType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class LocationService {

    private final OrientDBService orientDBService;
    private final UpdateService updateService;
    private static final Logger logger = LoggerFactory.getLogger(LocationService.class);

    @Autowired
    public LocationService(OrientDBService orientDBService, UpdateService updateService) {
        this.orientDBService = orientDBService;
        this.updateService = updateService;
    }

    public List<Location> getAllLocations() {
        List<Location> locations = new ArrayList<>();
        try (ODatabaseSession db = orientDBService.getSession()) {
            try (OResultSet rs = db.query("SELECT * FROM Location")) {
                while (rs.hasNext()) {
                    OResult row = rs.next();
                    row.getVertex().ifPresent(vertex -> {
                        Location location = vertexToLocation(vertex);
                        locations.add(location);
                    });
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Error while fetching locations: " + e.getMessage(), e);
        }

        return locations;
    }

    public Location getLocationById(String id) {
        try (ODatabaseSession db = orientDBService.getSession()) {
            var vertex = OrientDBUtils.loadAndValidateVertexByCustomId(db, id);
            Location location = vertexToLocation(vertex);
            return location;
        } catch (Exception e) {
            throw new RuntimeException("Error while fetching location with ID " + id + ": " + e.getMessage(), e);
        }
    }

    public Location createLocation(LocationInput location) {
        try (ODatabaseSession db = orientDBService.getSession()) {
            if (OrientDBUtils.checkIfAlreadyExists(db, location.getId())) {
                throw new IllegalArgumentException("Location with name " + location.getName() + " already exists.");
            }
            OVertex vertex = db.newVertex("Location");
            vertex.setProperty("name", location.getName());
            vertex.setProperty("customId", location.getId());
            vertex.setProperty("latitude", location.getLatitude());
            vertex.setProperty("longitude", location.getLongitude());
            vertex.setProperty("length", location.getLength());
            vertex.setProperty("speed", location.getSpeed());
            vertex.setProperty("type", location.getType());
            vertex.setProperty("active", location.getActive());
            vertex.save();
            return vertexToLocation(vertex);
        } catch (Exception e) {
            throw new RuntimeException(
                    "Error while creating location with name " + location.getName() + ": " + e.getMessage(), e);
        }
    }

    public Location updateLocation(UpdateModel model) {
        return vertexToLocation(this.updateService.updateVertex(model));
    }

    public Location fullUpdateLocation(Location location) {
        try (ODatabaseSession db = orientDBService.getSession()) {
            OVertex locationVertex = OrientDBUtils.loadAndValidateVertexByCustomId(db, location.getId());
    
            locationVertex.setProperty("name", location.getName());
            locationVertex.setProperty("latitude", location.getLatitude());
            locationVertex.setProperty("longitude", location.getLongitude());
            locationVertex.setProperty("length", location.getLength());
            locationVertex.setProperty("speed", location.getSpeed());
            locationVertex.setProperty("type", location.getType());
            locationVertex.setProperty("active", location.getActive());
            locationVertex.setProperty("properties", location.getProperties());
            
            reconcileConnections(db, locationVertex, location.getOutboundConnectionIds());
    
            locationVertex.save();
            
            return vertexToLocation(locationVertex);
    
        }
        catch (OConcurrentModificationException oce) {
            throw oce;
        } catch (Exception e) {
            throw new RuntimeException("Error during full update of location with ID " + location.getId(), e);
        }
    }

    /**
     * Delete a location and all connections to it
     * @param id
     */
    public void deleteLocation(String id) {
        try (ODatabaseSession db = orientDBService.getSession()) {
            db.begin();
            try {
                OVertex toLocationVertex = OrientDBUtils.loadAndValidateVertexByCustomId(db, id);

                for (OEdge edge : toLocationVertex.getEdges(ODirection.BOTH)) {
                    edge.delete();
                }

                toLocationVertex.delete();

                db.commit();
            } catch (Exception e) {
                db.rollback();
                throw new RuntimeException("Error while deleting location with ID " + id + ": " + e.getMessage(), e);
            }
        } catch (Exception e) {
            throw new RuntimeException("Error while deleting location with ID " + id + ": " + e.getMessage(), e);
        }
    }

    /**
     * A helper method to efficiently update the 'ConnectedTo' edges for a location.
     * It compares the current state in the DB with the desired state from the domain object
     * and only adds/removes the edges that have changed.
     */
    private void reconcileConnections(ODatabaseSession db, OVertex fromVertex, Set<String> desiredConnectionIds) {
        Map<String, OEdge> currentEdges = new HashMap<>();
        for (OEdge edge : fromVertex.getEdges(ODirection.OUT, "ConnectedTo")) {
            OVertex connectedVertex = edge.getTo();
            if (connectedVertex != null) {
                currentEdges.put(connectedVertex.getProperty("id"), edge);
            }
        }
        Set<String> currentConnectionIds = currentEdges.keySet();

        Set<String> idsToDelete = new HashSet<>(currentConnectionIds);
        idsToDelete.removeAll(desiredConnectionIds);
        
        for (String idToDelete : idsToDelete) {
            OEdge edgeToDelete = currentEdges.get(idToDelete);
            edgeToDelete.delete();
            logger.info("Deleted connection from {} to {}", fromVertex.getProperty("id"), idToDelete);
        }

        Set<String> idsToAdd = new HashSet<>(desiredConnectionIds);
        idsToAdd.removeAll(currentConnectionIds);

        for (String idToAdd : idsToAdd) {
            OVertex toLocationVertex = OrientDBUtils.loadAndValidateVertexByCustomId(db, idToAdd);
            fromVertex.addEdge(toLocationVertex, "ConnectedTo").save();
            logger.info("Created connection from {} to {}", fromVertex.getProperty("id"), idToAdd);
        }
    }

    private Location vertexToLocation(OVertex vertex) {
        if (vertex == null) {
            throw new IllegalArgumentException("Attempted to convert a null vertex to location.");
        }
        Integer capacity = vertex.getProperty("capacity");

        return new Location(
            vertex.getProperty("customId"),
            vertex.getProperty("name"),
            LocationType.fromString(vertex.getProperty("type")),
            vertex.getProperty("active"),
            vertex.getProperty("properties"),
            vertex.getProperty("latitude"),
            vertex.getProperty("longitude"),
            vertex.getProperty("length"),
            vertex.getProperty("speed"),
            capacity
        );
    }
}
