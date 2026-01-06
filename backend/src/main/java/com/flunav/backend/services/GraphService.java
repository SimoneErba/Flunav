package com.flunav.backend.services;

import com.flunav.backend.models.graph.GraphData;
import com.flunav.backend.models.response.ConveyorResponse;
import com.flunav.backend.models.response.ItemResponse;
import com.flunav.backend.models.response.LocationResponse;
import com.flunav.backend.repositories.LiveItemRepository;
import com.orientechnologies.orient.core.db.ODatabaseSession;
import com.orientechnologies.orient.core.sql.executor.OResult;
import com.orientechnologies.orient.core.sql.executor.OResultSet;
import flunav.types.ConveyorType;
import flunav.types.DisplayRule;
import flunav.types.LocationType;
import flunav.types.PositionType;
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
    private final DisplayRulesService displayRulesService;

    public GraphService(OrientDBService orientDBService,
            LiveItemRepository redisRepository,
            PathfindingService pathfindingService,
            DisplayRulesService displayRulesService) {
        this.orientDBService = orientDBService;
        this.redisRepository = redisRepository;
        this.pathfindingService = pathfindingService;
        this.displayRulesService = displayRulesService;
    }

    public GraphData getGraphData() {
        Instant now = Instant.now();
        Topology topology = fetchTopology();
        List<ItemResponse> activeItems = calculateAllItemStates(topology, now);

        var customDisplayRules = this.displayRulesService.getDisplayRules();

        for (var item : activeItems) {
            item.setCustomColor(this.displayRulesService.applyDisplayRules(item.getProperties(), customDisplayRules));
        }

        return new GraphData(
                new ArrayList<>(topology.nodeMap.values()),
                new ArrayList<>(topology.conveyorMap.values()),
                activeItems,
                now);
    }

    public List<ItemResponse> getAllItemStates() {
        return calculateAllItemStates(fetchTopology(), Instant.now());
    }

    private List<ItemResponse> calculateAllItemStates(Topology topology, Instant now) {
        // Fetch properties from OrientDB
        Map<String, Map<String, Object>> itemPropertiesMap = fetchItemProperties();

        List<Map<String, Object>> liveRawItems = redisRepository.getAllActiveItems();
        List<ItemResponse> activeItems = new ArrayList<>();
        List<String> itemsToRemove = new ArrayList<>();

        for (Map<String, Object> rawItem : liveRawItems) {
            String id = (String) rawItem.get("id");
            try {
                String positionId = (String) rawItem.get("positionId");
                PositionType type = (PositionType) rawItem.get("positionType");
                if (type == null)
                    type = PositionType.LOCATION;

                Long tsLong = (Long) rawItem.get("entryTimestamp");
                String destId = (String) rawItem.get("destinationId");
                Double accDist = (Double) rawItem.getOrDefault("accumulatedDistance", 0.0);

                if (positionId == null || tsLong == null)
                    continue;

                Instant entryTime = Instant.ofEpochMilli(tsLong);
                List<String> path = (List<String>) rawItem.get("path");

                // --- PATHFINDING (If missing) ---
                if ((path == null || path.isEmpty()) && destId != null) {
                    String startNode = null;
                    if (type == PositionType.LOCATION) {
                        startNode = positionId;
                    } else if (type == PositionType.CONVEYOR && topology.conveyorMap.containsKey(positionId)) {
                        startNode = topology.conveyorMap.get(positionId).getTargetId();
                    }

                    if (startNode != null) {
                        path = pathfindingService.calculateShortestPath(startNode, type, destId);
                    }
                }

                // Calculate state. Returns NULL if the item has reached a CHUTE.
                ItemResponse simulatedItem = calculateCurrentState(
                        id, positionId, type, entryTime, path, topology, now, accDist);

                if (simulatedItem != null) {
                    simulatedItem.setName((String) rawItem.get("name"));
                    simulatedItem.setProperties(itemPropertiesMap.getOrDefault(id, new HashMap<>()));
                    simulatedItem.setDestinationId(destId);
                    activeItems.add(simulatedItem);
                } else {
                    itemsToRemove.add(id);
                }
            } catch (Exception e) {
                logger.warn("Failed to process live item state for item", e);
                itemsToRemove.add(id);
            }
        }

        if (!itemsToRemove.isEmpty()) {
            logger.info("Lazy Cleanup: Removing {} finished items from Redis", itemsToRemove.size());
            redisRepository.deleteItems(itemsToRemove);
        }
        return activeItems;
    }

    private Map<String, Map<String, Object>> fetchItemProperties() {
        Map<String, Map<String, Object>> propertiesMap = new HashMap<>();
        try (ODatabaseSession session = orientDBService.getSession()) {
            String query = "SELECT customId, properties FROM Item";
            try (OResultSet rs = session.query(query)) {
                while (rs.hasNext()) {
                    OResult res = rs.next();
                    String id = res.getProperty("customId");
                    Map<String, Object> props = res.getProperty("properties");
                    if (id != null) {
                        propertiesMap.put(id, props != null ? props : new HashMap<>());
                    }
                }
            }
        }
        return propertiesMap;
    }

    private ItemResponse calculateCurrentState(
            String itemId, String startId, PositionType startType, Instant lastUpdate,
            List<String> path, Topology topo, Instant now, Double accDist) {

        Duration timeElapsed = Duration.between(lastUpdate, now);
        ConveyorResponse currentEdge = null;
        String lastNodeId = null;

        // --- 1. DETERMINE INITIAL STATE ---

        if (startType == PositionType.CONVEYOR && topo.conveyorMap.containsKey(startId)) {
            // CASE A: Started on an Edge
            currentEdge = topo.conveyorMap.get(startId);
            lastNodeId = currentEdge.getTargetId();
        } else if (startType == PositionType.LOCATION && topo.nodeMap.containsKey(startId)) {
            // CASE B: Started on a Node
            // Check immediately if we started on a CHUTE
            LocationResponse startNode = topo.nodeMap.get(startId);
            if (startNode != null && startNode.getType() == LocationType.CHUTE) {
                return null; // Item is already discharged
            }

            lastNodeId = startId;
            currentEdge = findNextEdge(startId, topo.outgoingEdgesMap, path);

            if (currentEdge == null) {
                return createItemResponse(itemId, null, startId, lastUpdate, 0.0);
            }
        } else {
            return null; // Invalid start position
        }

        // --- 2. TRAVERSE GRAPH ---
        while (currentEdge != null) {
            double speed = currentEdge.getSpeed() != null ? currentEdge.getSpeed() : 0.0;
            double length = currentEdge.getLength() != null ? currentEdge.getLength() : 1.0;

            double startOffset = (currentEdge.getId().equals(startId) && startType == PositionType.CONVEYOR) ? accDist
                    : 0.0;

            if (speed <= 0) {
                return createItemResponse(itemId, currentEdge.getId(), null, lastUpdate, startOffset / length);
            }

            long traversalTimeMillis = (long) (((length - startOffset) / speed) * 1000);
            Duration traversalDuration = Duration.ofMillis(traversalTimeMillis);

            // CHECK: Is item still on this edge?
            if (timeElapsed.compareTo(traversalDuration) < 0) {
                double distTraveled = (timeElapsed.toMillis() / 1000.0) * speed;
                double progress = (startOffset + distTraveled) / length;
                return createItemResponse(itemId, currentEdge.getId(), null, now.minus(timeElapsed), progress);
            }

            // NO: Item finished this edge.
            timeElapsed = timeElapsed.minus(traversalDuration);

            // We have arrived at the target node
            String arrivalNodeId = currentEdge.getTargetId();

            // --- CRITICAL CHANGE: CHUTE CHECK ---
            LocationResponse arrivalNode = topo.nodeMap.get(arrivalNodeId);
            if (arrivalNode != null && arrivalNode.getType() == LocationType.CHUTE) {
                // The item has arrived at a discharge point.
                // We return null so it is NOT added to the active list.
                // The frontend will simply not receive this item.
                return null;
            }
            // ------------------------------------

            lastNodeId = arrivalNodeId;
            currentEdge = findNextEdge(arrivalNodeId, topo.outgoingEdgesMap, path);
        }

        // 3. End of Line (Not a chute, but no more edges)
        // Check one last time if the final standing position is a chute
        LocationResponse endNode = topo.nodeMap.get(lastNodeId);
        if (endNode != null && endNode.getType() == LocationType.CHUTE) {
            return null;
        }

        return createItemResponse(itemId, null, lastNodeId, lastUpdate, 1.0);
    }

    /**
     * Finds the next edge from a node. Uses the Full Path List to decide direction.
     */
    private ConveyorResponse findNextEdge(
            String currentNodeId,
            Map<String, List<ConveyorResponse>> outgoing,
            List<String> path) {

        List<ConveyorResponse> edges = outgoing.get(currentNodeId);
        if (edges == null || edges.isEmpty())
            return null;

        // 1. Path Priority
        if (path != null && !path.isEmpty()) {
            int currentIndex = path.indexOf(currentNodeId);
            if (currentIndex >= 0 && currentIndex < path.size() - 1) {
                String nextTargetNodeId = path.get(currentIndex + 1);
                Optional<ConveyorResponse> match = edges.stream()
                        .filter(e -> e.getTargetId().equals(nextTargetNodeId))
                        .findFirst();
                if (match.isPresent())
                    return match.get();
            }
        }

        // 2. Main Path Priority
        Optional<ConveyorResponse> main = edges.stream()
                .filter(e -> Boolean.TRUE.equals(e.getIsMainPath()))
                .findFirst();
        if (main.isPresent())
            return main.get();

        // 3. Fallback
        return edges.size() > 1 ? null : edges.get(0);
    }

    private Topology fetchTopology() {
        Map<String, LocationResponse> nodeMap = new HashMap<>();
        Map<String, ConveyorResponse> conveyorMap = new ConcurrentHashMap<>();
        Map<String, List<ConveyorResponse>> outgoingEdgesMap = new HashMap<>();

        try (ODatabaseSession session = orientDBService.getSession()) {
            String nodeQuery = "SELECT customId, name, latitude, longitude, type, active, capacity, properties FROM Location";
            try (OResultSet rs = session.query(nodeQuery)) {
                while (rs.hasNext()) {
                    LocationResponse loc = mapToLocation(rs.next());
                    nodeMap.put(loc.getId(), loc);
                }
            }

            String edgeQuery = "SELECT customId, name, length, speed, type, active, isMainPath, capacity, out.customId as src, in.customId as tgt FROM Conveyor";
            try (OResultSet rs = session.query(edgeQuery)) {
                while (rs.hasNext()) {
                    ConveyorResponse conv = mapToConveyor(rs.next());
                    conveyorMap.put(conv.getId(), conv);
                    outgoingEdgesMap.computeIfAbsent(conv.getSourceId(), k -> new ArrayList<>()).add(conv);
                }
            }
        }
        return new Topology(nodeMap, conveyorMap, outgoingEdgesMap);
    }

    private ItemResponse createItemResponse(String id, String edgeId, String locId, Instant entry, Double progress) {
        ItemResponse item = new ItemResponse();
        item.setId(id);
        item.setCurrentEdgeId(edgeId);
        item.setLocationId(locId);
        item.setEntryTimestamp(entry);
        item.setProgress(Math.min(1.0, Math.max(0.0, progress)));
        item.setActive(true);
        return item;
    }

    private record Topology(
            Map<String, LocationResponse> nodeMap,
            Map<String, ConveyorResponse> conveyorMap,
            Map<String, List<ConveyorResponse>> outgoingEdgesMap) {
    }

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
        loc.setType(typeStr != null ? LocationType.valueOf(typeStr) : LocationType.GENERIC);
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
        conv.setProperties(res.getProperty("properties"));
        String typeStr = res.getProperty("type");
        conv.setType(typeStr != null ? ConveyorType.valueOf(typeStr) : ConveyorType.BELT);
        return conv;
    }
}