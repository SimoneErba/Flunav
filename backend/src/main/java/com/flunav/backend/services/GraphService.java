package com.flunav.backend.services;

import com.flunav.backend.models.graph.GraphData;
import com.flunav.backend.models.response.ConveyorResponse;
import com.flunav.backend.models.response.DisplayRuleColorResult;
import com.flunav.backend.models.response.DisplayRuleVisualStyle;
import com.flunav.backend.models.response.ItemResponse;
import com.flunav.backend.models.response.LocationResponse;
import com.flunav.backend.repositories.LiveConveyorRepository;
import com.orientechnologies.orient.core.db.ODatabaseSession;
import com.orientechnologies.orient.core.sql.executor.OResult;
import com.orientechnologies.orient.core.sql.executor.OResultSet;

import flunav.types.DisplayRule;
import flunav.types.RoutingStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import com.flunav.backend.context.DatabaseContextHolder;
import com.flunav.backend.repositories.support.MultiSimulationRuntimeStore;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class GraphService {
    private static final Logger logger = LoggerFactory.getLogger(GraphService.class);

    private final OrientDBService orientDBService;
    private final LiveConveyorRepository liveConveyorRepository;
    private final DisplayRulesService displayRulesService;
    private final SimulationService simulationService;
    private final TimeService timeService;
    private final org.modelmapper.ModelMapper modelMapper;
    private final TopologyProvider topologyProvider;
    private final StopwatchService stopwatchService;
    private final SensorMappingService sensorMappingService;
    private final MultiSimulationRuntimeStore runtimeStore;
    private final ItemPositionProjection itemPositionProjection;

    public GraphService(OrientDBService orientDBService,
            LiveConveyorRepository liveConveyorRepository,
            DisplayRulesService displayRulesService,
            @Lazy SimulationService simulationService,
            TimeService timeService,
            org.modelmapper.ModelMapper modelMapper,
            TopologyProvider topologyProvider,
            StopwatchService stopwatchService,
            SensorMappingService sensorMappingService,
            MultiSimulationRuntimeStore runtimeStore,
            ItemPositionProjection itemPositionProjection) {
        this.orientDBService = orientDBService;
        this.liveConveyorRepository = liveConveyorRepository;
        this.displayRulesService = displayRulesService;
        this.simulationService = simulationService;
        this.timeService = timeService;
        this.modelMapper = modelMapper;
        this.topologyProvider = topologyProvider;
        this.stopwatchService = stopwatchService;
        this.sensorMappingService = sensorMappingService;
        this.runtimeStore = runtimeStore;
        this.itemPositionProjection = itemPositionProjection;
    }

    public GraphData getGraphData() {
        var currentSimulation = simulationService.getCurrentSimulation();
        if (currentSimulation != null) {
            return getGraphData(simulationService.getSimulationClock(currentSimulation));
        }
        return getGraphData(timeService.now());
    }

    /** Design mode reads only topology, without computing item positions or occupancy. */
    public GraphData getTopologyData() {
        Topology topology = fetchTopology();
        return new GraphData(new ArrayList<>(topology.nodeMap.values()),
                new ArrayList<>(topology.conveyorMap.values()), List.of(), sensorMappingService.getMappings(), timeService.now());
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

            List<ItemResponse> activeItems = itemPositionProjection.calculateAllItemStates(topology, now, shouldCleanup, simulationId,
                    includeFinished, fetchItemProperties(), fetchItemPriorities());
            stopwatchService.stop(timer2, "All items state");

            var customDisplayRules = this.displayRulesService.getDisplayRules();

            for (var item : activeItems) {
                DisplayRuleVisualStyle style = this.displayRulesService.applyDisplayRules(
                        RuleFieldProjection.itemRootFields(item), item.getProperties(), customDisplayRules);
                if (style != null) {
                    item.setCustomColor(style.getFillColor());
                    item.setCustomBorderColor(style.getBorderColor());
                    item.setCustomBorderWidth(style.getBorderWidth());
                }
            }

            for (var loc : topology.nodeMap.values()) {
                DisplayRuleVisualStyle style = this.displayRulesService.applyDisplayRules(
                        RuleFieldProjection.locationRootFields(loc), loc.getProperties(), customDisplayRules);
                loc.setCustomColor(style != null ? style.getFillColor() : null);
            }

            for (var conv : topology.conveyorMap.values()) {
                conv.setFlowStopped(liveConveyorRepository.isFlowStopped(conv.getId()));
                DisplayRuleVisualStyle style = this.displayRulesService.applyDisplayRules(
                        RuleFieldProjection.conveyorRootFields(conv), conv.getProperties(), customDisplayRules);
                conv.setCustomColor(style != null ? style.getFillColor() : null);
            }

            return new GraphData(
                    new ArrayList<>(topology.nodeMap.values()),
                    new ArrayList<>(topology.conveyorMap.values()),
                    activeItems,
                    sensorMappingService.getMappings(),
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
        return itemPositionProjection.calculateAllItemStates(fetchTopology(), now, true, DatabaseContextHolder.getSimulationId(),
                false, fetchItemProperties(), fetchItemPriorities());
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
                        RuleFieldProjection.locationRootFields(e.getValue()), e.getValue().getProperties(), rules)) != null)
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> fillStyle(displayRulesService.applyDisplayRules(
                                RuleFieldProjection.locationRootFields(e.getValue()), e.getValue().getProperties(), rules))));

        Map<String, DisplayRuleVisualStyle> conveyorStyles = topology.conveyorMap.entrySet().stream()
                .filter(e -> fillStyle(displayRulesService.applyDisplayRules(
                        RuleFieldProjection.conveyorRootFields(e.getValue()), e.getValue().getProperties(), rules)) != null)
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> fillStyle(displayRulesService.applyDisplayRules(
                                RuleFieldProjection.conveyorRootFields(e.getValue()), e.getValue().getProperties(), rules))));

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
        var memory = runtimeStore.current();
        if (memory != null) {
            for (var item : memory.graph().items()) {
                propertiesMap.put(item.getId(), item.getProperties() != null ? item.getProperties() : Map.of());
            }
            return propertiesMap;
        }
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
            resp.setFlowStopped(liveConveyorRepository.isFlowStopped(resp.getId()));
            conveyorMap.put(resp.getId(), resp);
            outgoingEdgesMap.computeIfAbsent(resp.getSourceId(), k -> new ArrayList<>()).add(resp);
        }

        return new Topology(nodeMap, conveyorMap, outgoingEdgesMap);
    }





    /**
     * Loads durable item priorities from OrientDB for routing and styling display.
     * Redis keeps the live position state, but priority is metadata persisted with
     * the item record.
     */
    private Map<String, Double> fetchItemPriorities() {
        Map<String, Double> priorities = new HashMap<>();
        var memory = runtimeStore.current();
        if (memory != null) {
            for (var item : memory.graph().items()) {
                if (item.getPriority() != null) priorities.put(item.getId(), item.getPriority());
            }
            return priorities;
        }
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

    private DisplayRuleVisualStyle fillStyle(DisplayRuleVisualStyle style) {
        return style == null || style.getFillColor() == null
                ? null
                : new DisplayRuleVisualStyle(style.getFillColor(), null, null);
    }

    static record Topology(
            Map<String, LocationResponse> nodeMap,
            Map<String, ConveyorResponse> conveyorMap,
            Map<String, List<ConveyorResponse>> outgoingEdgesMap) {
    }
}
