package com.fiumen.backend.services;

import com.fiumen.backend.models.graph.GraphData;
import com.fiumen.backend.models.response.ConveyorResponse;
import com.fiumen.backend.models.response.ItemResponse;
import com.fiumen.backend.models.response.LocationResponse;
import com.fiumen.backend.repositories.LiveItemRepository;
import com.orientechnologies.orient.core.db.ODatabaseSession;
import com.orientechnologies.orient.core.sql.executor.OResult;
import com.orientechnologies.orient.core.sql.executor.OResultSet;

import fiumen.types.ConveyorType;
import fiumen.types.LocationType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class GraphService {
    private static final Logger logger = LoggerFactory.getLogger(GraphService.class);

    private final OrientDBService orientDBService;
    private final LiveItemRepository redisRepository;
    private final PathfindingService pathfindingService;

    public GraphService(OrientDBService orientDBService,
            LiveItemRepository redisRepository,
            PathfindingService pathfindingService) {
        this.orientDBService = orientDBService;
        this.redisRepository = redisRepository;
        this.pathfindingService = pathfindingService;
    }

    public GraphData getGraphData() {
        Instant now = Instant.now();

        List<LocationResponse> nodes = new ArrayList<>();
        Map<String, LocationResponse> nodeMap = new HashMap<>();
        Map<String, ConveyorResponse> conveyorMap = new ConcurrentHashMap<>();

        // Topology: SourceNodeID -> List of Outgoing Conveyors
        Map<String, List<ConveyorResponse>> outgoingEdgesMap = new HashMap<>();

        List<ItemResponse> activeItems = new ArrayList<>();

        // 1. FETCH TOPOLOGY
        try (ODatabaseSession session = orientDBService.getSession()) {
            // A. Fetch Nodes
            String nodeQuery = "SELECT customId, name, latitude, longitude, type, active, capacity, properties FROM Location";
            try (OResultSet rs = session.query(nodeQuery)) {
                while (rs.hasNext()) {
                    OResult res = rs.next();
                    LocationResponse loc = mapToLocation(res);
                    nodes.add(loc);
                    nodeMap.put(loc.getId(), loc);
                }
            }

            // B. Fetch Edges
            String edgeQuery = "SELECT customId, name, length, speed, type, active, isMainPath, capacity, out.customId as src, in.customId as tgt FROM Conveyor";
            try (OResultSet rs = session.query(edgeQuery)) {
                while (rs.hasNext()) {
                    OResult res = rs.next();
                    ConveyorResponse conv = mapToConveyor(res);
                    conveyorMap.put(conv.getId(), conv);

                    outgoingEdgesMap
                            .computeIfAbsent(conv.getSourceId(), k -> new ArrayList<>())
                            .add(conv);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Error fetching topology from OrientDB", e);
        }

        // 2. FETCH LIVE STATE
        List<Map<String, Object>> liveRawItems = redisRepository.getAllActiveItems();

        // 3. MERGE & SIMULATE
        for (Map<String, Object> rawItem : liveRawItems) {
            try {
                String id = (String) rawItem.get("id");
                String currentPositionId = (String) rawItem.get("edgeId");
                Long tsLong = (Long) rawItem.get("entryTimestamp");
                String destId = (String) rawItem.get("destinationId");

                if (currentPositionId == null || tsLong == null)
                    continue;

                Instant entryTime = Instant.ofEpochMilli(tsLong);
                List<String> path = null;

                // A. Pathfinding Logic
                if (destId != null) {
                    String startNodeForPathfinding = null;

                    if (nodeMap.containsKey(currentPositionId)) {
                        startNodeForPathfinding = currentPositionId;
                    } else if (conveyorMap.containsKey(currentPositionId)) {
                        ConveyorResponse c = conveyorMap.get(currentPositionId);
                        if (c != null)
                            startNodeForPathfinding = c.getTargetId();
                    }

                    if (startNodeForPathfinding != null) {
                        path = redisRepository.getCachedPath(startNodeForPathfinding, destId);
                        if (path == null) {
                            path = pathfindingService.calculateShortestPath(startNodeForPathfinding, destId);
                            if (!path.isEmpty()) {
                                redisRepository.cachePath(startNodeForPathfinding, destId, path);
                            }
                        }
                    }
                }

                // B. Calculate Position
                ItemResponse simulatedItem = calculateCurrentState(
                        id, currentPositionId, entryTime, path, conveyorMap, outgoingEdgesMap, now);

                if (simulatedItem != null) {
                    simulatedItem.setDestinationId(destId);
                    activeItems.add(simulatedItem);
                }

            } catch (Exception e) {
                logger.warn("Failed to process live item state for item", e);
            }
        }

        return new GraphData(nodes, new ArrayList<>(conveyorMap.values()), activeItems, now);
    }

    /**
     * Simulates item movement.
     * Handles starting on an Edge OR starting on a Node.
     */
    private ItemResponse calculateCurrentState(
            String itemId,
            String startId,
            Instant entryTime,
            List<String> path,
            Map<String, ConveyorResponse> conveyorMap,
            Map<String, List<ConveyorResponse>> outgoingEdgesMap,
            Instant now) {

        Duration timeElapsed = Duration.between(entryTime, now);
        ConveyorResponse currentEdge = null;

        // 1. Determine Initial Edge
        if (conveyorMap.containsKey(startId)) {
            // CASE A: Started on an Edge
            currentEdge = conveyorMap.get(startId);
        } else {
            // CASE B: Started on a Node (Location)
            // We must immediately find the outgoing edge to simulate movement.
            // Even if path is null, findNextEdge will look for Main Path.
            String nextEdgeIdFromPath = (path != null && !path.isEmpty()) ? path.get(0) : null;

            currentEdge = findNextEdge(startId, outgoingEdgesMap, nextEdgeIdFromPath);

            if (currentEdge == null) {
                // Stuck at a node (Sink or Ambiguous Path)
                return createItemResponse(itemId, null, startId, entryTime, 0.0);
            }

            // If we successfully picked the edge from the path, consume it
            if (nextEdgeIdFromPath != null && currentEdge.getId().equals(nextEdgeIdFromPath)) {
                path = new ArrayList<>(path); // Ensure mutable
                path.remove(0);
            }
        }

        // 2. Traverse the Graph
        while (currentEdge != null) {
            double speed = (currentEdge.getSpeed() != null) ? currentEdge.getSpeed() : 0.0;
            double length = (currentEdge.getLength() != null) ? currentEdge.getLength() : 1.0;

            if (speed <= 0) {
                // Stopped on this edge
                return createItemResponse(itemId, currentEdge.getId(), null, entryTime, 0.0);
            }

            long traversalTimeMillis = (long) ((length / speed) * 1000);
            Duration traversalDuration = Duration.ofMillis(traversalTimeMillis);

            // CHECK: Is item still on this edge?
            if (timeElapsed.compareTo(traversalDuration) < 0) {
                // YES: Calculate progress
                double progress = (double) timeElapsed.toMillis() / traversalTimeMillis;
                Instant virtualEntryTime = now.minus(timeElapsed);
                return createItemResponse(itemId, currentEdge.getId(), null, virtualEntryTime, progress);
            }

            // NO: Item finished this edge. Move to next.
            timeElapsed = timeElapsed.minus(traversalDuration);

            // Find next edge from the Target Node of the current edge
            String currentNodeId = currentEdge.getTargetId();
            String nextEdgeIdFromPath = (path != null && !path.isEmpty()) ? path.get(0) : null;

            currentEdge = findNextEdge(currentNodeId, outgoingEdgesMap, nextEdgeIdFromPath);

            if (currentEdge != null && nextEdgeIdFromPath != null && currentEdge.getId().equals(nextEdgeIdFromPath)) {
                if (!(path instanceof ArrayList))
                    path = new ArrayList<>(path);
                path.remove(0);
            }
        }

        // 3. End of Line (Sink)
        return createItemResponse(itemId, null, null, entryTime, 1.0);
    }

    /**
     * Finds the next edge from a node.
     * Priority:
     * 1. Specific Edge ID (from Pathfinding)
     * 2. Main Path
     * 3. Single available path
     * 4. If multiple non-main paths exist -> STOP (return null)
     */
    private ConveyorResponse findNextEdge(
            String nodeId,
            Map<String, List<ConveyorResponse>> outgoingEdgesMap,
            String specificNextEdgeId) {
        List<ConveyorResponse> edges = outgoingEdgesMap.get(nodeId);

        // 0. Dead End
        if (edges == null || edges.isEmpty()) {
            return null;
        }

        // 1. Use specific path instruction (from Pathfinding/Redis)
        if (specificNextEdgeId != null) {
            Optional<ConveyorResponse> match = edges.stream()
                    .filter(e -> e.getId().equals(specificNextEdgeId))
                    .findFirst();
            if (match.isPresent())
                return match.get();
        }

        // 2. Prioritize "Main Path"
        Optional<ConveyorResponse> mainPath = edges.stream()
                .filter(e -> Boolean.TRUE.equals(e.getIsMainPath()))
                .findFirst();

        if (mainPath.isPresent()) {
            return mainPath.get();
        }

        // 3. Ambiguity Check
        // If we are here, it means no specific path was found AND no Main Path exists.

        // If there is more than 1 option, we don't know which way to go -> STOP.
        if (edges.size() > 1) {
            return null;
        }

        // 4. Single Option Fallback
        // If there is exactly one outgoing edge, we follow it naturally.
        return edges.get(0);
    }

    private ItemResponse createItemResponse(String id, String edgeId, String locationId, Instant entryTime,
            Double progress) {
        ItemResponse item = new ItemResponse();
        item.setId(id);
        item.setCurrentEdgeId(edgeId);
        item.setLocationId(locationId);
        item.setEntryTimestamp(entryTime);
        item.setProgress(Math.min(1.0, Math.max(0.0, progress)));
        item.setActive(true);
        return item;
    }

    // --- Mappers ---

    private LocationResponse mapToLocation(OResult res) {
        LocationResponse loc = new LocationResponse();
        loc.setId(res.getProperty("customId"));
        loc.setName(res.getProperty("name"));
        loc.setLatitude(res.getProperty("latitude"));
        loc.setLongitude(res.getProperty("longitude"));
        loc.setActive(res.getProperty("active"));
        loc.setCapacity(res.getProperty("capacity"));
        loc.setProperties(res.getProperty("properties"));
        String typeStr = res.getProperty("type");
        if (typeStr != null) {
            try {
                loc.setType(LocationType.valueOf(typeStr));
            } catch (IllegalArgumentException e) {
                loc.setType(LocationType.GENERIC);
            }
        }
        return loc;
    }

    private ConveyorResponse mapToConveyor(OResult res) {
        ConveyorResponse conv = new ConveyorResponse();
        conv.setId(res.getProperty("customId"));
        conv.setName(res.getProperty("name"));
        conv.setSourceId(res.getProperty("src"));
        conv.setTargetId(res.getProperty("tgt"));
        conv.setLength(res.getProperty("length"));
        conv.setSpeed(res.getProperty("speed"));
        conv.setActive(res.getProperty("active"));
        conv.setIsMainPath(res.getProperty("isMainPath"));
        conv.setCapacity(res.getProperty("capacity"));
        String typeStr = res.getProperty("type");
        if (typeStr != null) {
            try {
                conv.setType(ConveyorType.valueOf(typeStr));
            } catch (IllegalArgumentException e) {
                conv.setType(ConveyorType.BELT);
            }
        } else {
            conv.setType(ConveyorType.BELT);
        }
        return conv;
    }
}