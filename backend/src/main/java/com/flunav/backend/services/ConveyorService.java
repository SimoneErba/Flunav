package com.flunav.backend.services;

import com.flunav.backend.domain.Conveyor;
import com.flunav.backend.utils.OrientDBUtils;
import com.orientechnologies.orient.core.db.ODatabaseSession;
import com.orientechnologies.orient.core.exception.OConcurrentModificationException;
import com.orientechnologies.orient.core.record.ODirection;
import com.orientechnologies.orient.core.record.OEdge;
import com.orientechnologies.orient.core.record.OVertex;
import com.orientechnologies.orient.core.sql.executor.OResult;
import com.orientechnologies.orient.core.sql.executor.OResultSet;
import flunav.types.ConveyorType;
import flunav.types.ActiveAlarm;
import flunav.types.AlarmSeverity;
import flunav.types.AlarmSource;
import flunav.types.ComponentType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.UUID;
import java.time.Instant;

@Service
public class ConveyorService {

    private static final Logger logger = LoggerFactory.getLogger(ConveyorService.class);
    private final OrientDBService orientDBService;
    private final com.flunav.backend.repositories.LiveConveyorRepository liveConveyorRepository;

    public ConveyorService(OrientDBService orientDBService,
            com.flunav.backend.repositories.LiveConveyorRepository liveConveyorRepository) {
        this.orientDBService = orientDBService;
        this.liveConveyorRepository = liveConveyorRepository;
    }

    /**
     * Retrieves all conveyors (edges) from the graph.
     */
    public List<Conveyor> getAllConveyors() {
        List<Conveyor> conveyors = new ArrayList<>();
        try (ODatabaseSession db = orientDBService.getSession()) {
            // Query all edges of class 'Conveyor'
            try (OResultSet rs = db.query("SELECT * FROM Conveyor")) {
                while (rs.hasNext()) {
                    OResult row = rs.next();
                    row.getEdge().ifPresent(edge -> conveyors.add(edgeToConveyor(edge)));
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Error fetching conveyors", e);
        }
        return conveyors;
    }

    /**
     * Retrieves a specific conveyor by its custom ID.
     */
    public Conveyor getConveyorById(String id) {
        try (ODatabaseSession db = orientDBService.getSession()) {
            // We use a parameterized query to find the edge by customId
            String query = "SELECT FROM Conveyor WHERE customId.toLowerCase() = ?";
            try (OResultSet rs = db.query(query, id.toLowerCase(Locale.ROOT))) {
                if (rs.hasNext()) {
                    OResult res = rs.next();
                    return res.getEdge().map(this::edgeToConveyor)
                            .orElseThrow(() -> new RuntimeException("Record is not an edge"));
                }
            }
            throw new RuntimeException("Conveyor not found with ID: " + id);
        }
    }

    /**
     * Creates a new Conveyor (Edge) between two Locations (Nodes).
     * Used by EventProcessor.
     */
    public Conveyor createConveyor(String connectionId, String sourceId, String targetId, String name,
            Double length, Double speed, Double minDistance, Boolean mainPath, Boolean isActive) {
        return createConveyor(connectionId, sourceId, targetId, name, length, speed, minDistance, mainPath, isActive,
                ConveyorType.BELT, null, Map.of());
    }

    public Conveyor createConveyor(String connectionId, String sourceId, String targetId, String name,
            Double length, Double speed, Double minDistance, Boolean mainPath, Boolean isActive,
            ConveyorType type, Integer capacity, Map<String, Object> properties) {
        try (ODatabaseSession db = orientDBService.getSession()) {
            db.begin();

            OVertex source = OrientDBUtils.loadAndValidateVertexByCustomId(db, sourceId);
            OVertex target = OrientDBUtils.loadAndValidateVertexByCustomId(db, targetId);

            // Check if exists
            for (OEdge e : source.getEdges(ODirection.OUT, "Conveyor")) {
                if (e.getTo().equals(target)) {
                    logger.warn("Conveyor already exists between {} and {}", sourceId, targetId);
                    return edgeToConveyor(e);
                }
            }

            // Create the Edge
            OEdge edge = source.addEdge(target, "Conveyor");

            // Use the provided connectionId, or fallback to a generated one if null
            String finalId = (connectionId != null && !connectionId.isBlank())
                    ? connectionId
                    : sourceId + "_" + targetId;

            edge.setProperty("customId", finalId);
            edge.setProperty("name", name != null ? name : "");

            // Set Physics & Properties from params (with safety defaults)
            edge.setProperty("length", length != null ? length : 10.0);
            edge.setProperty("speed", speed != null ? speed : 1.0);
            ConveyorType effectiveType = type != null ? type : ConveyorType.BELT;
            Double effectiveMinDistance = minDistance != null
                    ? minDistance
                    : effectiveType == ConveyorType.STAGING ? 0.1 : null;
            edge.setProperty("minDistance", effectiveMinDistance);
            edge.setProperty("active", isActive != null ? isActive : true);
            edge.setProperty("operatorEnabled", isActive != null ? isActive : true);
            edge.setProperty("activeAlarms", List.of());
            edge.setProperty("mainPath", mainPath != null ? mainPath : false);

            edge.setProperty("type", effectiveType.name());
            if (capacity != null) {
                edge.setProperty("capacity", capacity);
            }
            edge.setProperty("properties", properties != null ? properties : Map.of());

            edge.save();
            db.commit();

            return edgeToConveyor(edge);
        } catch (OConcurrentModificationException | java.util.NoSuchElementException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Error creating conveyor between " + sourceId + " and " + targetId, e);
        }
    }

    /**
     * Updates properties of an existing conveyor.
     */
    public void updateConveyor(Conveyor conveyor) {
        try (ODatabaseSession db = orientDBService.getSession()) {
            String query = "SELECT FROM Conveyor WHERE customId.toLowerCase() = ?";
            try (OResultSet rs = db.query(query, conveyor.getId().toLowerCase(Locale.ROOT))) {
                if (rs.hasNext()) {
                    OResult res = rs.next();
                    res.getEdge().ifPresent(edge -> {
                        edge.setProperty("speed", conveyor.getSpeed());
                        edge.setProperty("length", conveyor.getLength());
                        edge.setProperty("active", conveyor.isActive());
                        edge.setProperty("operatorEnabled", conveyor.isOperatorEnabled());
                        edge.setProperty("activeAlarms", serializeAlarms(conveyor.getActiveAlarms()));
                        edge.setProperty("mainPath", conveyor.isMainPath());
                        edge.setProperty("type", conveyor.getType().name());
                        edge.setProperty("minDistance", conveyor.getMinDistance());
                        edge.setProperty("properties", conveyor.getProperties());
                        if (conveyor.getCapacity() != null) {
                            edge.setProperty("capacity", conveyor.getCapacity());
                        }
                        edge.save();
                    });
                } else {
                    throw new RuntimeException("Conveyor not found: " + conveyor.getId());
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Error updating conveyor " + conveyor.getId(), e);
        }
    }

    /**
     * Deletes every conveyor edge between two nodes and then clears the matching
     * hot Redis state by conveyor id.
     */
    public void deleteConveyor(String sourceId, String targetId) {
        try (ODatabaseSession db = orientDBService.getSession()) {
            List<OEdge> conveyorsToDelete = new ArrayList<>();
            List<String> conveyorIds = new ArrayList<>();

            OVertex source = OrientDBUtils.loadAndValidateVertexByCustomId(db, sourceId);
            OVertex target = OrientDBUtils.loadAndValidateVertexByCustomId(db, targetId);

            for (OEdge edge : source.getEdges(ODirection.OUT, "Conveyor")) {
                if (!edge.getTo().equals(target)) {
                    continue;
                }
                conveyorsToDelete.add(edge);
                Object conveyorId = edge.getProperty("customId");
                if (conveyorId != null) {
                    conveyorIds.add(String.valueOf(conveyorId));
                }
            }

            if (conveyorIds.isEmpty()) {
                logger.warn("No conveyor found to delete between {} and {}", sourceId, targetId);
                return;
            }

            for (OEdge edge : conveyorsToDelete) {
                edge.delete();
            }

            for (String conveyorId : conveyorIds) {
                liveConveyorRepository.deleteConveyor(conveyorId);
            }
        } catch (Exception e) {
            throw new RuntimeException("Error deleting conveyor", e);
        }
    }

    /**
     * Retrieves all outgoing conveyors from a specific location.
     */
    public List<Conveyor> getOutgoingConveyors(String locationId) {
        List<Conveyor> conveyors = new ArrayList<>();
        try (ODatabaseSession db = orientDBService.getSession()) {
            OVertex location = OrientDBUtils.loadAndValidateVertexByCustomId(db, locationId);
            for (OEdge edge : location.getEdges(ODirection.OUT, "Conveyor")) {
                conveyors.add(edgeToConveyor(edge));
            }
        } catch (Exception e) {
            throw new RuntimeException("Error fetching outgoing conveyors for " + locationId, e);
        }
        return conveyors;
    }

    private Conveyor edgeToConveyor(OEdge edge) {
        String sourceId = edge.getFrom().getProperty("customId");
        String targetId = edge.getTo().getProperty("customId");

        String typeStr = edge.getProperty("type");
        ConveyorType type = (typeStr != null) ? ConveyorType.valueOf(typeStr) : ConveyorType.BELT;

        boolean active = edge.getProperty("active") != null ? edge.getProperty("active") : true;
        Boolean storedOperatorEnabled = edge.getProperty("operatorEnabled");
        Object storedAlarms = edge.getProperty("activeAlarms");
        if (storedOperatorEnabled == null || storedAlarms == null) {
            // Lazy, idempotent migration for conveyor edges created before alarms
            // and operator intent became first-class persisted state.
            edge.setProperty("operatorEnabled", storedOperatorEnabled != null ? storedOperatorEnabled : active);
            edge.setProperty("activeAlarms", storedAlarms != null ? storedAlarms : List.of());
            edge.save();
        }
        return new Conveyor(
                edge.getProperty("customId"),
                sourceId,
                targetId,
                edge.getProperty("length"),
                edge.getProperty("speed"),
                edge.getProperty("minDistance"),
                type,
                active,
                storedOperatorEnabled != null ? storedOperatorEnabled : active,
                deserializeAlarms(storedAlarms),
                edge.getProperty("capacity"),
                edge.getProperty("mainPath") != null ? edge.getProperty("mainPath") : false,
                edge.getProperty("properties"));
    }

    private List<Map<String, Object>> serializeAlarms(List<ActiveAlarm> alarms) {
        if (alarms == null) {
            return List.of();
        }
        return alarms.stream().map(alarm -> {
            Map<String, Object> value = new java.util.HashMap<>();
            value.put("alarmId", alarm.getAlarmId());
            value.put("conveyorId", alarm.getConveyorId());
            value.put("componentId", alarm.getComponentId());
            value.put("findingId", alarm.getFindingId());
            value.put("componentType", alarm.getComponentType().name());
            value.put("severity", alarm.getSeverity().name());
            value.put("typology", alarm.getTypology());
            value.put("source", alarm.getSource().name());
            value.put("stopsComponent", alarm.isStopsComponent());
            value.put("stopsConveyor", alarm.isStopsConveyor());
            value.put("raisedAt", alarm.getRaisedAt().toString());
            return value;
        }).toList();
    }

    private List<ActiveAlarm> deserializeAlarms(Object stored) {
        if (!(stored instanceof Iterable<?> values)) {
            return new ArrayList<>();
        }
        List<ActiveAlarm> alarms = new ArrayList<>();
        for (Object value : values) {
            if (!(value instanceof Map<?, ?> alarm)) {
                continue;
            }
            try {
                String componentId = String.valueOf(alarm.get("componentId") != null
                        ? alarm.get("componentId") : alarm.get("conveyorId"));
                boolean stops = Boolean.parseBoolean(String.valueOf(alarm.get("stopsComponent") != null
                        ? alarm.get("stopsComponent") : alarm.get("stopsConveyor")));
                alarms.add(new ActiveAlarm(String.valueOf(alarm.get("alarmId")), componentId,
                        alarm.get("findingId") != null ? String.valueOf(alarm.get("findingId")) : null,
                        ComponentType.CONVEYOR,
                        AlarmSeverity.valueOf(String.valueOf(alarm.get("severity"))),
                        String.valueOf(alarm.get("typology")),
                        alarm.get("source") != null
                                ? AlarmSource.valueOf(String.valueOf(alarm.get("source")))
                                : AlarmSource.MANUAL,
                        stops, Instant.parse(String.valueOf(alarm.get("raisedAt")))));
            } catch (RuntimeException exception) {
                logger.warn("Ignoring malformed active alarm on conveyor {}", edgeIdentifier(alarm), exception);
            }
        }
        return alarms;
    }

    private String edgeIdentifier(Map<?, ?> alarm) {
        Object conveyorId = alarm.get("conveyorId");
        return conveyorId != null ? conveyorId.toString() : "unknown";
    }
}
