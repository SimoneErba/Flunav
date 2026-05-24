package com.flunav.backend.services;

import com.flunav.backend.domain.Location;
import com.flunav.backend.models.UpdateModel;
import com.flunav.backend.models.input.LocationInput;
import com.flunav.backend.utils.OrientDBUtils;
import com.orientechnologies.orient.core.db.ODatabaseSession;
import com.orientechnologies.orient.core.exception.OConcurrentModificationException;
import com.orientechnologies.orient.core.record.ODirection;
import com.orientechnologies.orient.core.record.OEdge;
import com.orientechnologies.orient.core.record.OVertex;
import com.orientechnologies.orient.core.sql.executor.OResult;
import com.orientechnologies.orient.core.sql.executor.OResultSet;

import flunav.types.LocationType;
import flunav.types.PositionType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class LocationService {

    private final OrientDBService orientDBService;
    private final UpdateService updateService;
    private final TopologyProvider topologyProvider;
    private static final Logger logger = LoggerFactory.getLogger(LocationService.class);

    @Autowired
    public LocationService(OrientDBService orientDBService, UpdateService updateService,
            @org.springframework.context.annotation.Lazy TopologyProvider topologyProvider) {
        this.orientDBService = orientDBService;
        this.updateService = updateService;
        this.topologyProvider = topologyProvider;
    }

    public List<Location> getAllLocations() {
        List<Location> locations = new ArrayList<>();
        try (ODatabaseSession db = orientDBService.getSession()) {
            try (OResultSet rs = db.query("SELECT * FROM Location")) {
                while (rs.hasNext()) {
                    OResult row = rs.next();
                    row.getVertex().ifPresent(vertex -> {
                        locations.add(vertexToLocation(vertex));
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
            return vertexToLocation(vertex);
        } catch (Exception e) {
            throw new RuntimeException("Error while fetching location with ID " + id + ": " + e.getMessage(), e);
        }
    }

    public Location createLocation(LocationInput location) {
        try (ODatabaseSession db = orientDBService.getSession()) {
            if (OrientDBUtils.checkIfAlreadyExists(db, location.getId())) {
                throw new IllegalArgumentException("Location with ID " + location.getId() + " already exists.");
            }

            OVertex vertex = db.newVertex("Location");
            vertex.setProperty("name", location.getName());
            vertex.setProperty("customId", location.getId());
            vertex.setProperty("latitude", location.getLatitude());
            vertex.setProperty("longitude", location.getLongitude());
            vertex.setProperty("type", location.getType());
            vertex.setProperty("active", location.getActive());

            if (location.getCapacity() != null) {
                vertex.setProperty("capacity", location.getCapacity());
            }

            vertex.save();
            return vertexToLocation(vertex);
        } catch (Exception e) {
            throw new RuntimeException(
                    "Error while creating location " + location.getName() + ": " + e.getMessage(), e);
        }
    }

    public PositionType getPositionType(String id) {
        try (ODatabaseSession db = orientDBService.getSession()) {

            // 1. Check if ID exists in Location (Vertex)
            String locationQuery = "SELECT count(*) as count FROM Location WHERE customId.toLowerCase() = ?";
            String lowerId = id.toLowerCase(Locale.ROOT);
            try (OResultSet rs = db.query(locationQuery, lowerId)) {
                if (rs.hasNext()) {
                    Long count = rs.next().getProperty("count");
                    if (count != null && count > 0) {
                        return PositionType.LOCATION;
                    }
                }
            }

            // 2. Check if ID exists in Conveyor (Edge)
            String conveyorQuery = "SELECT count(*) as count FROM Conveyor WHERE customId.toLowerCase() = ?";
            try (OResultSet rs = db.query(conveyorQuery, lowerId)) {
                if (rs.hasNext()) {
                    Long count = rs.next().getProperty("count");
                    if (count != null && count > 0) {
                        return PositionType.CONVEYOR;
                    }
                }
            }

            throw new RuntimeException("ID " + id + " not found in Locations or Conveyors.");

        } catch (Exception e) {
            throw new RuntimeException("Error checking position type for ID " + id, e);
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
            locationVertex.setProperty("type", location.getType());
            locationVertex.setProperty("active", location.getActive());
            locationVertex.setProperty("properties", location.getProperties());

            locationVertex.setProperty("capacity", location.getCapacity());

            locationVertex.save();

            return vertexToLocation(locationVertex);

        } catch (OConcurrentModificationException | java.util.NoSuchElementException oce) {
            throw oce;
        } catch (Exception e) {
            throw new RuntimeException("Error during full update of location with ID " + location.getId(), e);
        }
    }

    public void deleteLocation(String id) {
        try (ODatabaseSession db = orientDBService.getSession()) {
            db.begin();
            try {
                OVertex toLocationVertex = OrientDBUtils.loadAndValidateVertexByCustomId(db, id);

                // When deleting a Node, we must delete all connected Edges
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

    private Location vertexToLocation(OVertex vertex) {
        if (vertex == null) {
            return null;
        }

        Integer capacity = vertex.getProperty("capacity");

        String typeStr = vertex.getProperty("type");

        LocationType type;
        try {
            type = typeStr != null ? LocationType.valueOf(typeStr) : LocationType.GENERIC;
        } catch (IllegalArgumentException e) {
            type = LocationType.GENERIC;
        }

        return new Location(
                vertex.getProperty("customId"),
                vertex.getProperty("name"),
                type,
                vertex.getProperty("active"),
                vertex.getProperty("properties"),
                vertex.getProperty("latitude"),
                vertex.getProperty("longitude"),
                capacity);
    }
}
