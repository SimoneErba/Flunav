package com.flunav.backend.services;

import com.flunav.backend.models.RedisLiveItem;
import com.flunav.backend.models.graph.GraphData;
import com.flunav.backend.models.response.ConveyorResponse;
import com.flunav.backend.models.response.DisplayRuleColorResult;
import com.flunav.backend.models.response.ItemResponse;
import com.flunav.backend.models.response.LocationResponse;
import com.flunav.backend.repositories.LiveItemRepository;
import com.orientechnologies.orient.core.db.ODatabaseSession;
import com.orientechnologies.orient.core.sql.executor.OResult;
import com.orientechnologies.orient.core.sql.executor.OResultSet;

import flunav.types.DisplayRule;
import flunav.types.PositionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import com.flunav.backend.context.DatabaseContextHolder;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class GraphService {
    private static final Logger logger = LoggerFactory.getLogger(GraphService.class);

    private final OrientDBService orientDBService;
    private final LiveItemRepository redisRepository;
    private final PathfindingService pathfindingService;
    private final DisplayRulesService displayRulesService;
    private final SimulationService simulationService;
    private final TimeService timeService;
    private final org.modelmapper.ModelMapper modelMapper;
    private final TopologyProvider topologyProvider;
    private final StopwatchService stopwatchService;

    public GraphService(OrientDBService orientDBService,
            LiveItemRepository redisRepository,
            PathfindingService pathfindingService,
            DisplayRulesService displayRulesService,
            SimulationService simulationService,
            TimeService timeService,
            org.modelmapper.ModelMapper modelMapper,
            TopologyProvider topologyProvider,
            StopwatchService stopwatchService) {
        this.orientDBService = orientDBService;
        this.redisRepository = redisRepository;
        this.pathfindingService = pathfindingService;
        this.displayRulesService = displayRulesService;
        this.simulationService = simulationService;
        this.timeService = timeService;
        this.modelMapper = modelMapper;
        this.topologyProvider = topologyProvider;
        this.stopwatchService = stopwatchService;
    }

    public GraphData getGraphData() {
        var currentSimulation = simulationService.getCurrentSimulation();
        if (currentSimulation != null) {
            return getGraphData(simulationService.getSimulationClock(currentSimulation));
        }
        return getGraphData(timeService.now());
    }

    public GraphData getGraphData(Instant now) {
        return getGraphData(now, true, DatabaseContextHolder.getSimulationId(), false);
    }

    public GraphData getGraphData(Instant now, boolean shouldCleanup) {
        return getGraphData(now, shouldCleanup, DatabaseContextHolder.getSimulationId(), false);
    }

    public GraphData getGraphData(Instant now, boolean shouldCleanup, String simulationId) {
        return getGraphData(now, shouldCleanup, simulationId, false);
    }

    public GraphData getGraphData(Instant now, boolean shouldCleanup, String simulationId, boolean includeFinished) {
        String timer = stopwatchService.start();
        try (var ctx = (simulationId != null) ? DatabaseContextHolder.enterSimulationContext(simulationId) : null) {
            Topology topology = fetchTopology();
            stopwatchService.stop(timer, "Fetch tipology");
            String timer2 = stopwatchService.start();

            List<ItemResponse> activeItems = calculateAllItemStates(topology, now, shouldCleanup, simulationId,
                    includeFinished);
            stopwatchService.stop(timer2, "All items state");

            var customDisplayRules = this.displayRulesService.getDisplayRules();

            for (var item : activeItems) {
                item.setCustomColor(
                        this.displayRulesService.applyDisplayRules(item.getProperties(), customDisplayRules));
            }

            for (var loc : topology.nodeMap.values()) {
                loc.setCustomColor(this.displayRulesService.applyDisplayRules(loc.getProperties(), customDisplayRules));
            }

            for (var conv : topology.conveyorMap.values()) {
                conv.setCustomColor(
                        this.displayRulesService.applyDisplayRules(conv.getProperties(), customDisplayRules));
            }

            return new GraphData(
                    new ArrayList<>(topology.nodeMap.values()),
                    new ArrayList<>(topology.conveyorMap.values()),
                    activeItems,
                    now);
        }
    }

    public List<ItemResponse> getAllItemStates() {
        var currentSimulation = simulationService.getCurrentSimulation();
        Instant now = currentSimulation != null ? simulationService.getSimulationClock(currentSimulation)
                : timeService.now();
        return calculateAllItemStates(fetchTopology(), now, true, DatabaseContextHolder.getSimulationId(),
                false);
    }

    private List<ItemResponse> calculateAllItemStates(Topology topology, Instant now, boolean shouldCleanup,
            String simulationId, boolean includeFinished) {
        // Fetch properties from OrientDB
        Map<String, Map<String, Object>> itemPropertiesMap = fetchItemProperties();

        List<RedisLiveItem> liveRawItems = redisRepository.getAllActiveItems();

        List<ItemResponse> activeItems = new ArrayList<>();
        List<String> itemsToRemove = new ArrayList<>();

        for (RedisLiveItem rawItem : liveRawItems) {
            String id = rawItem.getId();
            try {
                String positionId = rawItem.getPositionId();
                PositionType type = rawItem.getType();
                if (type == null)
                    type = PositionType.LOCATION;

                Instant entryTime = rawItem.getEntryTime();
                List<String> destinations = rawItem.getDestinations();
                String selectedExitId = rawItem.getSelectedExitId();
                Double accDist = rawItem.getAccumulatedDistance();

                if (positionId == null || entryTime == null)
                    continue;

                List<String> path = rawItem.getPath();

                // --- PATHFINDING (If missing) ---
                if (path == null && selectedExitId != null) {
                    String startNode = null;
                    if (type == PositionType.LOCATION) {
                        startNode = positionId;
                    } else if (type == PositionType.CONVEYOR && topology.conveyorMap.containsKey(positionId)) {
                        startNode = topology.conveyorMap.get(positionId).getTargetId();
                    }

                    if (startNode != null) {
                        path = pathfindingService.calculateShortestPath(startNode, type, selectedExitId);
                    }
                }

                ItemResponse simulatedItem = calculateCurrentState(
                        id, positionId, type, entryTime, path, topology, now, accDist);

                if (simulatedItem != null) {
                    simulatedItem.setName(rawItem.getName());
                    simulatedItem.setProperties(itemPropertiesMap.getOrDefault(id, new HashMap<>()));
                    simulatedItem.setDestinations(destinations);
                    simulatedItem.setSelectedExitId(selectedExitId);
                    simulatedItem.setPath(path);
                    activeItems.add(simulatedItem);
                } else {
                    if (includeFinished) {
                        ItemResponse finished = new ItemResponse();
                        finished.setId(id);
                        finished.setName(rawItem.getName());
                        finished.setActive(false);
                        finished.setProgress(1.0);
                        finished.setCurrentEdgeId(positionId);
                        finished.setProperties(itemPropertiesMap.getOrDefault(id, new HashMap<>()));
                        activeItems.add(finished);
                    }
                    if (shouldCleanup && simulationId == null) {
                        itemsToRemove.add(id);
                    }
                }
            } catch (Exception e) {
                logger.warn("Failed to process live item state for item {}", id, e);
                if (shouldCleanup && simulationId == null)
                    itemsToRemove.add(id);
            }
        }

        if (shouldCleanup && !itemsToRemove.isEmpty() && simulationId == null) {
            logger.info("Lazy Cleanup: Removing {} finished items from Redis", itemsToRemove.size());
            redisRepository.deleteItems(itemsToRemove);
        }
        return activeItems;
    }

    public DisplayRuleColorResult computeColors(List<DisplayRule> rules) {
        Topology topology = fetchTopology();

        Map<String, String> locationColors = topology.nodeMap.entrySet().stream()
                .filter(e -> displayRulesService.applyDisplayRules(e.getValue().getProperties(), rules) != null)
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> displayRulesService.applyDisplayRules(e.getValue().getProperties(), rules)));

        Map<String, String> conveyorColors = topology.conveyorMap.entrySet().stream()
                .filter(e -> displayRulesService.applyDisplayRules(e.getValue().getProperties(), rules) != null)
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> displayRulesService.applyDisplayRules(e.getValue().getProperties(), rules)));

        // Items need live state — fetch only IDs + properties, no physics calculation
        Map<String, Map<String, Object>> items = fetchItemProperties();
        Map<String, String> itemColors = items.entrySet().stream()
                .filter(e -> displayRulesService.applyDisplayRules(e.getValue(), rules) != null)
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> displayRulesService.applyDisplayRules(e.getValue(), rules)));

        return new DisplayRuleColorResult(itemColors, locationColors, conveyorColors);
    }

    private Map<String, Map<String, Object>> fetchItemProperties() {
        Map<String, Map<String, Object>> propertiesMap = new HashMap<>();
        try (ODatabaseSession session = orientDBService.getSession()) {
            if (session == null)
                return propertiesMap;
            String query = "SELECT customId, properties FROM Item";
            try (OResultSet rs = session.query(query)) {
                if (rs == null)
                    return propertiesMap;
                while (rs.hasNext()) {
                    OResult res = rs.next();
                    String id = res.getProperty("customId");
                    Map<String, Object> props = res.getProperty("properties");
                    if (id != null) {
                        propertiesMap.put(id, props != null ? props : new HashMap<>());
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Could not fetch item properties from OrientDB");
        }
        return propertiesMap;
    }

    private ItemResponse calculateCurrentState(
            String itemId, String startId, PositionType startType, Instant lastUpdate,
            List<String> path, Topology topo, Instant now, Double accDist) {
        Duration timeElapsed = Duration.between(lastUpdate, now);
        if (timeElapsed.isNegative())
            timeElapsed = Duration.ZERO;

        ConveyorResponse currentEdge = null;
        String lastNodeId = null;

        // --- 1. DETERMINE INITIAL STATE ---

        if (startType == PositionType.CONVEYOR && topo.conveyorMap.containsKey(startId)) {
            // CASE A: Started on an Edge
            currentEdge = topo.conveyorMap.get(startId);
            lastNodeId = currentEdge.getTargetId();
        } else if (startType == PositionType.LOCATION && topo.nodeMap.containsKey(startId)) {
            // CASE B: Started on a Node
            lastNodeId = startId;
            currentEdge = findNextEdge(startId, topo.outgoingEdgesMap, path);

            if (currentEdge == null) {
                return createItemResponse(itemId, null, startId, lastUpdate, 0.0);
            }
        } else {
            return null;
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
                double distanceOnCurrentEdge = startOffset + distTraveled;
                double progress = distanceOnCurrentEdge / length;
                Instant currentEdgeEntryTime = now.minusMillis((long) ((distanceOnCurrentEdge / speed) * 1000));
                return createItemResponse(itemId, currentEdge.getId(), null, currentEdgeEntryTime, progress);
            }

            // NO: Item finished this edge.
            timeElapsed = timeElapsed.minus(traversalDuration);

            // We have arrived at the target node
            String arrivalNodeId = currentEdge.getTargetId();

            lastNodeId = arrivalNodeId;
            currentEdge = findNextEdge(arrivalNodeId, topo.outgoingEdgesMap, path);
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
                .filter(e -> Boolean.TRUE.equals(e.getMainPath()))
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

        for (var loc : topologyProvider.getAllLocations()) {
            var resp = modelMapper.map(loc, LocationResponse.class);
            nodeMap.put(resp.getId(), resp);
        }

        for (var conv : topologyProvider.getAllConveyors()) {
            var resp = modelMapper.map(conv, ConveyorResponse.class);
            conveyorMap.put(resp.getId(), resp);
            outgoingEdgesMap.computeIfAbsent(resp.getSourceId(), k -> new ArrayList<>()).add(resp);
        }

        return new Topology(nodeMap, conveyorMap, outgoingEdgesMap);
    }

    private ItemResponse createItemResponse(String id, String edgeId, String locId, Instant entry, Double progress) {
        ItemResponse item = new ItemResponse();
        item.setId(id);
        item.setCurrentEdgeId(edgeId);
        item.setLocationId(edgeId == null ? locId : null);
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
}
