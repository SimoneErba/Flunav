package com.flunav.backend.services;

import com.flunav.backend.models.RedisLiveItem;
import com.flunav.backend.models.graph.GraphData;
import com.flunav.backend.models.response.ConveyorResponse;
import com.flunav.backend.models.response.DisplayRuleColorResult;
import com.flunav.backend.models.response.DisplayRuleVisualStyle;
import com.flunav.backend.models.response.ItemResponse;
import com.flunav.backend.models.response.LocationResponse;
import com.flunav.backend.repositories.LiveItemRepository;
import com.orientechnologies.orient.core.db.ODatabaseSession;
import com.orientechnologies.orient.core.sql.executor.OResult;
import com.orientechnologies.orient.core.sql.executor.OResultSet;

import flunav.types.DisplayRule;
import flunav.types.PositionType;
import flunav.types.RoutingStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
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
            @Lazy SimulationService simulationService,
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

    /**
     * Builds the graph snapshot for the requested clock and context.
     * The simulation context is entered here so topology, Redis item state, and
     * display rules are read from the same live or simulation namespace.
     */
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
                DisplayRuleVisualStyle style = this.displayRulesService.applyDisplayRules(
                        itemRootFields(item), item.getProperties(), customDisplayRules);
                if (style != null) {
                    item.setCustomColor(style.getFillColor());
                    item.setCustomBorderColor(style.getBorderColor());
                    item.setCustomBorderWidth(style.getBorderWidth());
                }
            }

            for (var loc : topology.nodeMap.values()) {
                DisplayRuleVisualStyle style = this.displayRulesService.applyDisplayRules(
                        locationRootFields(loc), loc.getProperties(), customDisplayRules);
                loc.setCustomColor(style != null ? style.getFillColor() : null);
            }

            for (var conv : topology.conveyorMap.values()) {
                DisplayRuleVisualStyle style = this.displayRulesService.applyDisplayRules(
                        conveyorRootFields(conv), conv.getProperties(), customDisplayRules);
                conv.setCustomColor(style != null ? style.getFillColor() : null);
            }

            return new GraphData(
                    new ArrayList<>(topology.nodeMap.values()),
                    new ArrayList<>(topology.conveyorMap.values()),
                    activeItems,
                    now);
        }
    }

    /**
     * Returns projected item states for the active graph context.
     * Simulation clocks are used when a simulation is active so rendered positions
     * match the simulation timeline instead of wall-clock time.
     */
    public List<ItemResponse> getAllItemStates() {
        var currentSimulation = simulationService.getCurrentSimulation();
        Instant now = currentSimulation != null ? simulationService.getSimulationClock(currentSimulation)
                : timeService.now();
        return calculateAllItemStates(fetchTopology(), now, true, DatabaseContextHolder.getSimulationId(),
                false);
    }

    /**
     * Projects Redis hot item state into frontend item DTOs at the requested clock.
     * Cleanup is limited to live mode because simulation state must remain
     * replayable while future projections are still being built.
     */
    private List<ItemResponse> calculateAllItemStates(Topology topology, Instant now, boolean shouldCleanup,
            String simulationId, boolean includeFinished) {
        Map<String, Map<String, Object>> itemPropertiesMap = fetchItemProperties();
        Map<String, Double> itemPriorities = fetchItemPriorities();

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
                RoutingStatus routingStatus = effectiveRoutingStatus(rawItem.getRoutingStatus(), selectedExitId);
                Instant routingStatusUpdatedAt = rawItem.getRoutingStatusUpdatedAt();
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
                    simulatedItem.setPriority(itemPriorities.get(id));
                    simulatedItem.setProperties(itemPropertiesMap.getOrDefault(id, new HashMap<>()));
                    simulatedItem.setDestinations(destinations);
                    simulatedItem.setSelectedExitId(selectedExitId);
                    simulatedItem.setRoutingStatus(routingStatus);
                    simulatedItem.setRoutingStatusUpdatedAt(routingStatusUpdatedAt);
                    simulatedItem.setPath(path);
                    activeItems.add(simulatedItem);
                } else {
                    if (includeFinished) {
                        ItemResponse finished = new ItemResponse();
                        finished.setId(id);
                        finished.setName(rawItem.getName());
                        finished.setPriority(itemPriorities.get(id));
                        finished.setActive(false);
                        finished.setProgress(1.0);
                        finished.setCurrentEdgeId(positionId);
                        finished.setProperties(itemPropertiesMap.getOrDefault(id, new HashMap<>()));
                        finished.setRoutingStatus(RoutingStatus.COMPLETED);
                        finished.setRoutingStatusUpdatedAt(now);
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

    /**
     * Previews the colors a proposed rule set would apply to graph entities.
     * This runs the same matching logic as getGraphData without mutating the saved
     * display-rule configuration.
     */
    public DisplayRuleColorResult computeColors(List<DisplayRule> rules) {
        Topology topology = fetchTopology();

        Map<String, DisplayRuleVisualStyle> locationStyles = topology.nodeMap.entrySet().stream()
                .filter(e -> fillStyle(displayRulesService.applyDisplayRules(
                        locationRootFields(e.getValue()), e.getValue().getProperties(), rules)) != null)
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> fillStyle(displayRulesService.applyDisplayRules(
                                locationRootFields(e.getValue()), e.getValue().getProperties(), rules))));

        Map<String, DisplayRuleVisualStyle> conveyorStyles = topology.conveyorMap.entrySet().stream()
                .filter(e -> fillStyle(displayRulesService.applyDisplayRules(
                        conveyorRootFields(e.getValue()), e.getValue().getProperties(), rules)) != null)
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> fillStyle(displayRulesService.applyDisplayRules(
                                conveyorRootFields(e.getValue()), e.getValue().getProperties(), rules))));

        Map<String, Map<String, Object>> items = fetchItemProperties();
        Map<String, Double> priorities = fetchItemPriorities();
        Map<String, DisplayRuleVisualStyle> itemStyles = items.entrySet().stream()
                .filter(e -> displayRulesService.applyDisplayRules(
                        Map.of("id", e.getKey(), "priority", priorities.getOrDefault(e.getKey(), 0.0)),
                        e.getValue(), rules) != null)
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> displayRulesService.applyDisplayRules(
                                Map.of("id", e.getKey(), "priority", priorities.getOrDefault(e.getKey(), 0.0)),
                                e.getValue(), rules)));

        return new DisplayRuleColorResult(itemStyles, locationStyles, conveyorStyles);
    }

    /**
     * Loads durable item custom properties from OrientDB for rule evaluation.
     * Position and routing are intentionally not read here because Redis owns the
     * hot state used by graph projection.
     */
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

    /**
     * Replays movement from the last checkpoint instead of trusting a stored screen
     * position. This lets live, historical, and simulated graph reads derive the
     * same visible state from timestamped movement data.
     */
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
            Duration processingDuration = processingDuration(topo.nodeMap.get(startId));
            if (!processingDuration.isZero()) {
                if (timeElapsed.compareTo(processingDuration) < 0) {
                    return createItemResponse(itemId, null, startId, lastUpdate, 1.0);
                }
                timeElapsed = timeElapsed.minus(processingDuration);
            }
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
            Duration processingDuration = processingDuration(topo.nodeMap.get(arrivalNodeId));
            if (!processingDuration.isZero()) {
                Instant arrivalTime = now.minus(timeElapsed);
                if (timeElapsed.compareTo(processingDuration) < 0) {
                    return createItemResponse(itemId, null, arrivalNodeId, arrivalTime, 1.0);
                }
                timeElapsed = timeElapsed.minus(processingDuration);
            }
            currentEdge = findNextEdge(arrivalNodeId, topo.outgoingEdgesMap, path);
        }

        return createItemResponse(itemId, null, lastNodeId, lastUpdate, 1.0);
    }

    /**
     * Converts timed-node metadata into a graph-projection delay.
     * Only timed nodes wait; all other location types remain pass-through for
     * animation and snapshot projection.
     */
    private Duration processingDuration(LocationResponse location) {
        long delayMillis = location == null
                ? 0L
                : processingDelayMillis(location);
        return delayMillis <= 0L ? Duration.ZERO : Duration.ofMillis(delayMillis);
    }

    private long processingDelayMillis(LocationResponse location) {
        Long delay = location.getTimeToProcessMs();
        return location.getType() == flunav.types.LocationType.TIMED_NODE && delay != null && delay > 0L
                ? delay
                : 0L;
    }

    /**
     * Finds the next edge from a node. Uses the Full Path List to decide direction.
     * If no path applies, main-path selection keeps passive flow deterministic and
     * ambiguous split points stay unresolved instead of guessing a random branch.
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

    /**
     * Materializes topology once per graph read.
     * Item projection uses this stable node/edge view even though topology and hot
     * item state are loaded through different repositories.
     */
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

    /**
     * Loads durable item priorities from OrientDB for routing and styling display.
     * Redis keeps the live position state, but priority is metadata persisted with
     * the item record.
     */
    private Map<String, Double> fetchItemPriorities() {
        Map<String, Double> priorities = new HashMap<>();
        try (ODatabaseSession session = orientDBService.getSession()) {
            if (session == null) {
                return priorities;
            }
            try (OResultSet rs = session.query("SELECT customId, priority FROM Item")) {
                while (rs != null && rs.hasNext()) {
                    OResult result = rs.next();
                    String id = result.getProperty("customId");
                    Number priority = result.getProperty("priority");
                    if (id != null && priority != null) {
                        priorities.put(id, priority.doubleValue());
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Could not fetch item priorities from OrientDB");
        }
        return priorities;
    }

    /**
     * Exposes first-class item fields to display-rule evaluation.
     * These fields take precedence over custom properties with the same names.
     */
    private Map<String, Object> itemRootFields(ItemResponse item) {
        Map<String, Object> fields = new HashMap<>();
        fields.put("id", item.getId());
        fields.put("name", item.getName());
        fields.put("active", item.getActive());
        fields.put("priority", item.getPriority());
        fields.put("locationId", item.getLocationId());
        fields.put("currentEdgeId", item.getCurrentEdgeId());
        fields.put("entryTimestamp", item.getEntryTimestamp());
        fields.put("progress", item.getProgress());
        fields.put("routingStatus", item.getRoutingStatus());
        return fields;
    }

    /**
     * Exposes first-class location fields to display-rule evaluation.
     * Keeping these separate from custom properties lets rules target topology
     * attributes without relying on duplicated property values.
     */
    private Map<String, Object> locationRootFields(LocationResponse location) {
        Map<String, Object> fields = new HashMap<>();
        fields.put("id", location.getId());
        fields.put("name", location.getName());
        fields.put("type", location.getType());
        fields.put("active", location.getActive());
        fields.put("capacity", location.getCapacity());
        fields.put("latitude", location.getLatitude());
        fields.put("longitude", location.getLongitude());
        fields.put("timeToProcessMs", location.getTimeToProcessMs());
        return fields;
    }

    /**
     * Exposes first-class conveyor fields to display-rule evaluation.
     * These values come from topology state rather than Redis hot state.
     */
    private Map<String, Object> conveyorRootFields(ConveyorResponse conveyor) {
        Map<String, Object> fields = new HashMap<>();
        fields.put("id", conveyor.getId());
        fields.put("name", conveyor.getName());
        fields.put("sourceId", conveyor.getSourceId());
        fields.put("targetId", conveyor.getTargetId());
        fields.put("length", conveyor.getLength());
        fields.put("speed", conveyor.getSpeed());
        fields.put("active", conveyor.getActive());
        fields.put("mainPath", conveyor.getMainPath());
        fields.put("capacity", conveyor.getCapacity());
        return fields;
    }

    private DisplayRuleVisualStyle fillStyle(DisplayRuleVisualStyle style) {
        return style == null || style.getFillColor() == null
                ? null
                : new DisplayRuleVisualStyle(style.getFillColor(), null, null);
    }

    /**
     * Backfills routing status for older Redis item hashes.
     * Historical or live state without the explicit field keeps the previous
     * selected-exit semantics so graph reads remain compatible.
     */
    private RoutingStatus effectiveRoutingStatus(RoutingStatus status, String selectedExitId) {
        if (status != null) {
            return status;
        }
        return selectedExitId == null ? RoutingStatus.UNROUTED : RoutingStatus.ASSIGNED;
    }

    private record Topology(
            Map<String, LocationResponse> nodeMap,
            Map<String, ConveyorResponse> conveyorMap,
            Map<String, List<ConveyorResponse>> outgoingEdgesMap) {
    }
}
