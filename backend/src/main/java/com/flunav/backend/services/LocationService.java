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
import flunav.types.ActiveAlarm;
import flunav.types.AlarmSeverity;
import flunav.types.AlarmSource;
import flunav.types.ComponentType;

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
            vertex.setProperty("properties", location.getProperties());
            vertex.setProperty("activeAlarms", List.of());

            if (location.getCapacity() != null) {
                vertex.setProperty("capacity", location.getCapacity());
            }
            if (location.getTimeToProcessMs() != null) {
                vertex.setProperty("timeToProcessMs", location.getTimeToProcessMs());
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
            locationVertex.setProperty("timeToProcessMs", location.getTimeToProcessMs());
            locationVertex.setProperty("activeAlarms", serializeAlarms(location.getActiveAlarms()));

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
        Long timeToProcessMs = numberToLong(vertex.getProperty("timeToProcessMs"));

        String typeStr = vertex.getProperty("type");

        LocationType type;
        try {
            type = typeStr != null ? LocationType.valueOf(typeStr) : LocationType.GENERIC;
        } catch (IllegalArgumentException e) {
            type = LocationType.GENERIC;
        }

        Location location = new Location(
                vertex.getProperty("customId"),
                vertex.getProperty("name"),
                type,
                vertex.getProperty("active"),
                vertex.getProperty("properties"),
                vertex.getProperty("latitude"),
                vertex.getProperty("longitude"),
                capacity,
                timeToProcessMs);
        location.setActiveAlarms(deserializeAlarms(vertex.getProperty("activeAlarms"), location.getId()));
        return location;
    }

    private List<java.util.Map<String, Object>> serializeAlarms(List<ActiveAlarm> alarms) {
        if (alarms == null) {
            return List.of();
        }
        return alarms.stream().map(alarm -> {
            java.util.Map<String, Object> value = new java.util.HashMap<>();
            value.put("alarmId", alarm.getAlarmId());
            value.put("componentId", alarm.getComponentId());
            value.put("findingId", alarm.getFindingId());
            value.put("componentType", alarm.getComponentType().name());
            value.put("severity", alarm.getSeverity().name());
            value.put("typology", alarm.getTypology());
            value.put("source", alarm.getSource().name());
            value.put("stopsComponent", alarm.isStopsComponent());
            value.put("raisedAt", alarm.getRaisedAt().toString());
            return value;
        }).toList();
    }

    private List<ActiveAlarm> deserializeAlarms(Object stored, String locationId) {
        if (!(stored instanceof Iterable<?> values)) {
            return new ArrayList<>();
        }
        List<ActiveAlarm> alarms = new ArrayList<>();
        for (Object value : values) {
            if (!(value instanceof java.util.Map<?, ?> alarm)) {
                continue;
            }
            try {
                alarms.add(new ActiveAlarm(String.valueOf(alarm.get("alarmId")), locationId,
                        alarm.get("findingId") != null ? String.valueOf(alarm.get("findingId")) : null,
                        ComponentType.LOCATION,
                        AlarmSeverity.valueOf(String.valueOf(alarm.get("severity"))),
                        String.valueOf(alarm.get("typology")),
                        alarm.get("source") != null
                                ? AlarmSource.valueOf(String.valueOf(alarm.get("source")))
                                : AlarmSource.MANUAL,
                        Boolean.parseBoolean(String.valueOf(alarm.get("stopsComponent"))),
                        java.time.Instant.parse(String.valueOf(alarm.get("raisedAt")))));
            } catch (RuntimeException exception) {
                logger.warn("Ignoring malformed active alarm on location {}", locationId, exception);
            }
        }
        return alarms;
    }

    private Long numberToLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }
}
