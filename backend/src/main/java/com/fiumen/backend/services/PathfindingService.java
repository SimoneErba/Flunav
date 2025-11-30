package com.fiumen.backend.services;

import com.orientechnologies.orient.core.db.ODatabaseSession;
import com.orientechnologies.orient.core.record.ODirection;
import com.orientechnologies.orient.core.record.OEdge;
import com.orientechnologies.orient.core.record.OVertex;
import com.orientechnologies.orient.core.sql.executor.OResult;
import com.orientechnologies.orient.core.sql.executor.OResultSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Service
public class PathfindingService {

    private static final Logger logger = LoggerFactory.getLogger(PathfindingService.class);
    private final OrientDBService orientDBService;

    public PathfindingService(OrientDBService orientDBService) {
        this.orientDBService = orientDBService;
    }

    /**
     * Calculates the shortest path between two nodes based on physical transit
     * time.
     *
     * @param sourceNodeId      The custom ID of the starting Node (Waypoint).
     * @param destinationNodeId The custom ID of the target Node (Waypoint).
     * @return An ordered list of CONVEYOR IDs (Edge IDs) representing the path.
     */
    public List<String> calculateShortestPath(String sourceNodeId, String destinationNodeId) {
        // 1. Define the Weight Function (JavaScript)
        // This runs on the EDGE.
        // Logic:
        // - If 'fixedTransitTime' exists (e.g. Gravity Roller), use it.
        // - Else, calculate Time = Length / Speed.
        // - Else, return a small default cost.
        String weightFunction = "function(edge) {" +
                "  var fixedTime = edge.getProperty('fixedTransitTime');" +
                "  if (fixedTime != null && fixedTime > 0) {" +
                "    return fixedTime / 1000.0;" + // Convert ms to seconds
                "  }" +
                "  var len = edge.getProperty('length');" +
                "  var spd = edge.getProperty('speed');" +
                "  if (len != null && spd != null && spd > 0) {" +
                "    return len / spd;" + // Time = Distance / Speed
                "  }" +
                "  return 0.1;" + // Small cost for zero-length/logical connections
                "}";

        // 2. Execute Dijkstra
        // Note: We select FROM Location (Nodes)
        String query = "SELECT dijkstra(" +
                "  (SELECT FROM Location WHERE customId = :source), " +
                "  (SELECT FROM Location WHERE customId = :dest), " +
                "  ?, " + // The weight function
                "  'OUT', " +
                "  'Conveyor'" + // Only traverse edges of class 'Conveyor'
                ") AS path";

        try (ODatabaseSession db = orientDBService.getSession()) {
            OResultSet rs = db.query(query, Map.of("source", sourceNodeId, "dest", destinationNodeId), weightFunction);

            if (rs.hasNext()) {
                OResult result = rs.next();
                List<OVertex> pathVertices = result.getProperty("path");

                if (pathVertices == null || pathVertices.isEmpty()) {
                    return Collections.emptyList();
                }

                // 3. Convert Node Path to Edge Path
                // Dijkstra returns [NodeA, NodeB, NodeC].
                // We need [Edge_A_to_B, Edge_B_to_C].
                return convertVertexPathToEdgePath(pathVertices);
            }
        } catch (Exception e) {
            logger.error("Error calculating shortest path from {} to {}", sourceNodeId, destinationNodeId, e);
            return Collections.emptyList();
        }

        return Collections.emptyList();
    }

    /**
     * Helper to find the connecting edges between a sequence of vertices.
     */
    private List<String> convertVertexPathToEdgePath(List<OVertex> vertices) {
        List<String> edgePath = new ArrayList<>();

        for (int i = 0; i < vertices.size() - 1; i++) {
            OVertex current = vertices.get(i);
            OVertex next = vertices.get(i + 1);

            // Find the edge connecting Current -> Next
            String edgeId = findConnectingEdgeId(current, next);
            if (edgeId != null) {
                edgePath.add(edgeId);
            } else {
                logger.warn("Pathfinding discontinuity: No edge found between {} and {}",
                        current.getProperty("customId"), next.getProperty("customId"));
                // Break or continue? Usually break as path is broken.
                break;
            }
        }
        return edgePath;
    }

    /**
     * Finds the 'Conveyor' edge connecting two vertices.
     */
    private String findConnectingEdgeId(OVertex from, OVertex to) {
        // Iterate outgoing edges of type 'Conveyor'
        for (OEdge edge : from.getEdges(ODirection.OUT, "Conveyor")) {
            // Check if this edge points to the 'to' vertex
            if (edge.getTo().equals(to)) {
                return edge.getProperty("customId");
            }
        }
        return null;
    }
}