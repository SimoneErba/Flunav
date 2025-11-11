package com.fiumen.backend.services;

import com.orientechnologies.orient.core.db.ODatabaseSession;
import com.orientechnologies.orient.core.sql.executor.OResult;
import com.orientechnologies.orient.core.sql.executor.OResultSet;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class PathfindingService {

    private final OrientDBService orientDBService;

    public PathfindingService(OrientDBService orientDBService) {
        this.orientDBService = orientDBService;
    }

    /**
     * Calculates the shortest path between any two nodes based on transit/process
     * time.
     * It returns the full list of all nodes in the path.
     *
     * @param sourceNodeId      The custom ID of the starting node.
     * @param destinationNodeId The custom ID of the target node.
     * @return An ordered list of ALL node IDs representing the shortest path,
     *         or an empty list if no path is found.
     */
    public List<String> calculateShortestPath(String sourceNodeId, String destinationNodeId) {
        // This weight function is now capability-based.
        // It prioritizes 'processTime', then calculates from 'speed' and 'length'.
        String weightFunction = "function(edge) {" +
                "  var fromVertex = edge.getVertex('out');" +
                "  var processTime = fromVertex.getProperty('processTime');" +
                "  if (processTime != null && processTime > 0) {" +
                "    return processTime;" + // Use direct process time if available
                "  }" +
                "  var speed = fromVertex.getProperty('speed');" +
                "  var length = fromVertex.getProperty('length');" +
                "  if (speed != null && speed > 0 && length != null && length > 0) {" +
                "    return length / speed;" + // Otherwise, calculate from speed/length
                "  } else {" +
                "    return 0;" + // Assume instantaneous transfer if no time properties
                "  }" +
                "}";

        // The query is now simpler, as it works on any 'Location' vertex, regardless of
        // type.
        String query = "SELECT dijkstra(" +
                "  (SELECT FROM Location WHERE customId = :source), " +
                "  (SELECT FROM Location WHERE customId = :dest), " +
                "  ?, " + // Placeholder for the custom function
                "  'OUT'" +
                ") AS path";

        try (ODatabaseSession db = orientDBService.getSession()) {
            OResultSet rs = db.query(query, Map.of("source", sourceNodeId, "dest", destinationNodeId), weightFunction);

            if (rs.hasNext()) {
                OResult result = rs.next();
                List<String> fullPathOfRIDs = result.getProperty("path");

                if (fullPathOfRIDs == null || fullPathOfRIDs.isEmpty()) {
                    return Collections.emptyList();
                }

                // --- Return ALL nodes in the path ---

                // Query all nodes in the path to get their customId.
                String conversionQuery = "SELECT customId FROM [" + String.join(",", fullPathOfRIDs) + "]";

                try (OResultSet conversionRs = db.query(conversionQuery)) {
                    return conversionRs.stream()
                            .map(r -> (String) r.getProperty("customId"))
                            .collect(Collectors.toList());
                }
            }
        } catch (Exception e) {
            System.err.println("Error calculating shortest path with Dijkstra: " + e.getMessage());
            return Collections.emptyList();
        }

        return Collections.emptyList();
    }
}