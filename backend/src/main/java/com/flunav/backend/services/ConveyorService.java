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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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
            String query = "SELECT FROM Conveyor WHERE customId = ?";
            try (OResultSet rs = db.query(query, id)) {
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
            edge.setProperty("minDistance", minDistance);
            edge.setProperty("active", isActive != null ? isActive : true);
            edge.setProperty("mainPath", mainPath != null ? mainPath : false);

            // Static default
            edge.setProperty("type", ConveyorType.BELT.name());

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
            String query = "SELECT FROM Conveyor WHERE customId = ?";
            try (OResultSet rs = db.query(query, conveyor.getId())) {
                if (rs.hasNext()) {
                    OResult res = rs.next();
                    res.getEdge().ifPresent(edge -> {
                        edge.setProperty("speed", conveyor.getSpeed());
                        edge.setProperty("length", conveyor.getLength());
                        edge.setProperty("active", conveyor.isActive());
                        edge.setProperty("mainPath", conveyor.isMainPath());
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
     * Deletes a conveyor between two nodes.
     */
    public void deleteConveyor(String sourceId, String targetId) {
        try (ODatabaseSession db = orientDBService.getSession()) {
            OVertex source = OrientDBUtils.loadAndValidateVertexByCustomId(db, sourceId);
            OVertex target = OrientDBUtils.loadAndValidateVertexByCustomId(db, targetId);

            for (OEdge edge : source.getEdges(ODirection.OUT, "Conveyor")) {
                if (edge.getTo().equals(target)) {
                    edge.delete();
                    return;
                }
            }
            logger.warn("No conveyor found to delete between {} and {}", sourceId, targetId);
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

        return new Conveyor(
                edge.getProperty("customId"),
                sourceId,
                targetId,
                edge.getProperty("length"),
                edge.getProperty("speed"),
                edge.getProperty("minDistance"),
                type,
                edge.getProperty("active") != null ? edge.getProperty("active") : true,
                edge.getProperty("capacity"),
                edge.getProperty("mainPath") != null ? edge.getProperty("mainPath") : false,
                edge.getProperty("properties"));
    }
}
