package com.flonav.backend.services;

import com.orientechnologies.orient.core.db.ODatabaseSession;
import com.orientechnologies.orient.core.record.ODirection;
import com.orientechnologies.orient.core.record.OEdge;
import com.orientechnologies.orient.core.record.OVertex;
import com.orientechnologies.orient.core.sql.executor.OResult;
import com.orientechnologies.orient.core.sql.executor.OResultSet;

import flonav.types.PositionType;

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
     * Calculates the shortest path.
     * If type is LOCATION: Starts from that Location.
     * If type is CONVEYOR: Starts from the END (Target Node) of that Conveyor.
     */
    public List<String> calculateShortestPath(String sourceId, PositionType type, String destinationNodeId) {

        // 1. Determine the Source Vertex Sub-Query
        String sourceLetClause;
        if (type == PositionType.CONVEYOR) {
            // If on a conveyor, start from its target node (in)
            sourceLetClause = "$src = (SELECT expand(in) FROM Conveyor WHERE customId = :source)";
        } else {
            // If at a location, start from that location
            sourceLetClause = "$src = (SELECT FROM Location WHERE customId = :source)";
        }

        // 2. Define the Destination LET clause
        String destLetClause = "$dst = (SELECT FROM Location WHERE customId = :dest)";

        // 2. Define the Weight Function (JavaScript)
        // (Same as before)
        String weightFunction = "function(edge) {" +
                "  var fixedTime = edge.getProperty('fixedTransitTime');" +
                "  if (fixedTime != null && fixedTime > 0) {" +
                "    return fixedTime / 1000.0;" +
                "  }" +
                "  var len = edge.getProperty('length');" +
                "  var spd = edge.getProperty('speed');" +
                "  if (len != null && spd != null && spd > 0) {" +
                "    return len / spd;" +
                "  }" +
                "  return 0.1;" +
                "}";

        // 3. Execute Dijkstra
        // We inject the sourceSubQuery determined above
        String query = "SELECT $path.customId as path " +
                "LET " + sourceLetClause + ", " + destLetClause + ", $path = dijkstra($src, $dst, :weightFunc, 'OUT')";

        try (ODatabaseSession db = orientDBService.getSession()) {
            OResultSet rs = db.query(query, Map.of("source", sourceId, "dest", destinationNodeId, "weightFunc",
                    weightFunction));

            if (rs.hasNext()) {
                OResult result = rs.next();
                List<String> pathVertices = result.getProperty("path");

                if (pathVertices == null || pathVertices.isEmpty()) {
                    return Collections.emptyList();
                }

                // 4. Convert Node Path to Edge Path
                return pathVertices;
            }
        } catch (Exception e) {
            logger.error("Error calculating shortest path from {} ({}) to {}", sourceId, type, destinationNodeId, e);
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