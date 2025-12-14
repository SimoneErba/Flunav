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
        Topology topology = fetchTopology();
        List<ItemResponse> activeItems = calculateAllItemStates(topology, now);

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
        List<Map<String, Object>> liveRawItems = redisRepository.getAllActiveItems();
        List<ItemResponse> activeItems = new ArrayList<>();

        for (Map<String, Object> rawItem : liveRawItems) {
            try {
                String id = (String) rawItem.get("id");
                String currentPositionId = (String) rawItem.get("edgeId");
                Long tsLong = (Long) rawItem.get("entryTimestamp");
                String destId = (String) rawItem.get("destinationId");
                Double accDist = (Double) rawItem.getOrDefault("accumulatedDistance", 0.0);

                if (currentPositionId == null || tsLong == null)
                    continue;

                Instant entryTime = Instant.ofEpochMilli(tsLong);
                List<String> path = (List<String>) rawItem.get("path");

                // Pathfinding fallback
                if ((path == null || path.isEmpty()) && destId != null) {
                    String startNode = topology.nodeMap.containsKey(currentPositionId) ? currentPositionId
                            : topology.conveyorMap.get(currentPositionId).getTargetId();
                    path = pathfindingService.calculateShortestPath(startNode, destId);
                }

                ItemResponse simulatedItem = calculateCurrentState(
                        id, currentPositionId, entryTime, path, topology, now, accDist);

                if (simulatedItem != null) {
                    simulatedItem.setDestinationId(destId);
                    activeItems.add(simulatedItem);
                }
            } catch (Exception e) {
                logger.warn("Failed to process live item state for item", e);
            }
        }
        return activeItems;
    }

    private ItemResponse calculateCurrentState(
            String itemId, String startId, Instant lastUpdate, List<String> path,
            Topology topo, Instant now, Double accDist) {

        Duration timeElapsed = Duration.between(lastUpdate, now);
        ConveyorResponse currentEdge = null;
        String lastNodeId = null;

        if (topo.conveyorMap.containsKey(startId)) {
            currentEdge = topo.conveyorMap.get(startId);
            lastNodeId = currentEdge.getTargetId();
        } else {
            lastNodeId = startId;
            String nextEdgeId = (path != null && !path.isEmpty()) ? path.get(0) : null;
            currentEdge = findNextEdge(startId, topo.outgoingEdgesMap, nextEdgeId);
            if (currentEdge == null)
                return createItemResponse(itemId, null, startId, lastUpdate, 0.0);
            if (nextEdgeId != null && currentEdge.getId().equals(nextEdgeId)) {
                path = new ArrayList<>(path);
                path.remove(0);
            }
        }

        while (currentEdge != null) {
            double speed = currentEdge.getSpeed() != null ? currentEdge.getSpeed() : 0.0;
            double length = currentEdge.getLength() != null ? currentEdge.getLength() : 1.0;

            // Only apply accumulated distance to the very first edge in the calculation
            double startOffset = currentEdge.getId().equals(startId) ? accDist : 0.0;

            if (speed <= 0)
                return createItemResponse(itemId, currentEdge.getId(), null, lastUpdate, startOffset / length);

            long traversalTimeMillis = (long) (((length - startOffset) / speed) * 1000);
            Duration traversalDuration = Duration.ofMillis(traversalTimeMillis);

            if (timeElapsed.compareTo(traversalDuration) < 0) {
                double distTraveled = (timeElapsed.toMillis() / 1000.0) * speed;
                double progress = (startOffset + distTraveled) / length;
                return createItemResponse(itemId, currentEdge.getId(), null, now.minus(timeElapsed), progress);
            }

            timeElapsed = timeElapsed.minus(traversalDuration);
            lastNodeId = currentEdge.getTargetId();
            String nextEdgeId = (path != null && !path.isEmpty()) ? path.get(0) : null;
            currentEdge = findNextEdge(lastNodeId, topo.outgoingEdgesMap, nextEdgeId);

            if (currentEdge != null && nextEdgeId != null && currentEdge.getId().equals(nextEdgeId)) {
                if (!(path instanceof ArrayList))
                    path = new ArrayList<>(path);
                path.remove(0);
            }
        }

        return createItemResponse(itemId, null, lastNodeId, lastUpdate, 1.0);
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

    private ConveyorResponse findNextEdge(String nodeId, Map<String, List<ConveyorResponse>> outgoing,
            String specificId) {
        List<ConveyorResponse> edges = outgoing.get(nodeId);
        if (edges == null || edges.isEmpty())
            return null;
        if (specificId != null) {
            return edges.stream().filter(e -> e.getId().equals(specificId)).findFirst().orElse(null);
        }
        Optional<ConveyorResponse> main = edges.stream().filter(e -> Boolean.TRUE.equals(e.getIsMainPath()))
                .findFirst();
        if (main.isPresent())
            return main.get();
        return edges.size() > 1 ? null : edges.get(0);
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

    // Internal DTO to pass topology around
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
        String typeStr = res.getProperty("type");
        conv.setType(typeStr != null ? ConveyorType.valueOf(typeStr) : ConveyorType.BELT);
        return conv;
    }
}