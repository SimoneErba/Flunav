package com.flunav.backend.controllers;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.flunav.backend.context.DatabaseContextHolder;
import com.flunav.backend.domain.Conveyor;
import com.flunav.backend.models.RedisLiveItem;
import com.flunav.backend.models.analytics.EntityEventType;
import com.flunav.backend.models.graph.GraphData;
import com.flunav.backend.models.response.AlarmHistoryRecord;
import com.flunav.backend.models.response.EntityEventRecord;
import com.flunav.backend.models.response.ThroughputMetric;
import com.flunav.backend.repositories.LiveItemRepository;
import com.flunav.backend.repositories.LiveSimulationRepository;
import com.flunav.backend.repositories.AnomalyObservationRepository;
import com.flunav.backend.models.analytics.AnomalyFinding;
import com.flunav.backend.models.analytics.AnomalyIncident;
import com.flunav.backend.services.ClickHouseService;
import com.flunav.backend.services.ConveyorService;
import com.flunav.backend.services.GraphService;
import com.flunav.backend.services.ThroughputBucketService;
import com.flunav.backend.services.TimeService;
import com.flunav.backend.services.ItemService;
import com.flunav.backend.services.RoutingDecisionService;

@RestController
@RequestMapping("/api/analytics/investigation")
public class InvestigationAnalyticsController {
    private static final List<Integer> SUPPORTED_BUCKETS = List.of(60, 300, 900, 3600);

    private final GraphService graphService;
    private final ConveyorService conveyorService;
    private final LiveItemRepository liveItemRepository;
    private final ClickHouseService clickHouseService;
    private final ThroughputBucketService throughputBucketService;
    private final TimeService timeService;
    private final ItemService itemService;
    private final RoutingDecisionService routingDecisionService;
    private final LiveSimulationRepository liveSimulationRepository;
    private final AnomalyObservationRepository anomalyRepository;

    public InvestigationAnalyticsController(
            GraphService graphService,
            ConveyorService conveyorService,
            LiveItemRepository liveItemRepository,
            ClickHouseService clickHouseService,
            ThroughputBucketService throughputBucketService,
            TimeService timeService,
            ItemService itemService,
            RoutingDecisionService routingDecisionService,
            LiveSimulationRepository liveSimulationRepository,
            AnomalyObservationRepository anomalyRepository) {
        this.graphService = graphService;
        this.conveyorService = conveyorService;
        this.liveItemRepository = liveItemRepository;
        this.clickHouseService = clickHouseService;
        this.throughputBucketService = throughputBucketService;
        this.timeService = timeService;
        this.itemService = itemService;
        this.routingDecisionService = routingDecisionService;
        this.liveSimulationRepository = liveSimulationRepository;
        this.anomalyRepository = anomalyRepository;
    }

    public record Envelope<T>(T data, Meta meta, ApiError error) {
        static <T> Envelope<T> ok(T data) {
            return new Envelope<>(data,
                    new Meta(DatabaseContextHolder.getSimulationId(), Instant.now()), null);
        }
    }

    public record Meta(String simulationId, Instant generatedAt) {
    }

    public record ApiError(String code, String message) {
    }

    public record ThroughputComparison(long entered, long exited, long baselineEntered, long baselineExited) {
    }

    public record SystemSummary(long locations, long conveyors, long activeConveyors, long activeItems,
            long activeAlarms, long stoppingAlarms, List<ThroughputMetric> throughput,
            ThroughputComparison comparison) {
    }

    public record AlarmInvestigation(String alarmId, List<AlarmHistoryRecord> history,
            List<String> affectedItems, List<ThroughputMetric> throughput, String conclusion) {
    }

    public record ItemSummary(String itemId, String name, String positionId, String positionType,
            List<String> destinations, String selectedExitId, List<String> path) {
    }

    public record ComponentSummary(String componentId, String componentType, boolean active,
            int occupancy, Integer capacity, Double speed, List<?> activeAlarms) {
    }

    public record ConveyorFlow(String conveyorId, String sourceId, String targetId, boolean active,
            boolean operatorEnabled, int occupancy, Integer capacity) {
    }

    public record DestinationSummary(String destinationId, long assignedItems, long candidateItems) {
    }

    public record SimulationSummary(String simulationId, Instant restoreTimestamp, String status,
            Instant lastProcessedTimestamp, double speedFactor, double buildProgress) {
    }

    public record RerouteOption(String itemId, String conveyorId, String destinationId,
            List<String> path, int availableCapacity, boolean advisoryOnly) {
    }

    public record RerouteAssessment(List<RerouteOption> options, List<String> upstreamReroutableItems,
            List<String> committedItems, boolean preservesAssignedDestination, boolean advisoryOnly) {
    }

    /**
     * Reads throughput through the active live or simulation context so the
     * comparison window never mixes projected simulation facts into live history.
     */
    @GetMapping("/system/summary")
    public CompletableFuture<Envelope<SystemSummary>> systemSummary(
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "60") int bucketSeconds) {
        Window window = window(from, to, bucketSeconds);
        GraphData graph = graphService.getGraphData();
        long activeConveyors = graph.getConveyors().stream().filter(edge -> Boolean.TRUE.equals(edge.getActive())).count();
        long activeAlarms = graph.getConveyors().stream()
                .mapToLong(edge -> edge.getActiveAlarms() != null ? edge.getActiveAlarms().size() : 0).sum();
        long stoppingAlarms = graph.getConveyors().stream()
                .filter(edge -> edge.getActiveAlarms() != null)
                .flatMap(edge -> edge.getActiveAlarms().stream())
                .filter(alarm -> alarm.isStopsConveyor()).count();
        String simulationId = DatabaseContextHolder.getSimulationId();
        CompletableFuture<List<ThroughputMetric>> current = simulationId == null
                ? clickHouseService.getThroughputHistory(window.from(), window.to(), bucketSeconds)
                : throughputBucketService.getSimulationHistory(simulationId, window.from(), window.to(), bucketSeconds);
        Instant baselineFrom = window.from().minusSeconds(window.to().getEpochSecond() - window.from().getEpochSecond());
        CompletableFuture<List<ThroughputMetric>> baseline = simulationId == null
                ? clickHouseService.getThroughputHistory(baselineFrom, window.from(), bucketSeconds)
                : throughputBucketService.getSimulationHistory(simulationId, baselineFrom, window.from(), bucketSeconds);
        return current.thenCombine(baseline, (throughput, baselineThroughput) -> Envelope.ok(new SystemSummary(
                graph.getLocations().size(), graph.getConveyors().size(), activeConveyors,
                graph.getItems().size(), activeAlarms, stoppingAlarms, throughput,
                new ThroughputComparison(sumEntered(throughput), sumExited(throughput),
                        sumEntered(baselineThroughput), sumExited(baselineThroughput)))));
    }

    @GetMapping("/topology")
    public Envelope<GraphData> topology() {
        return Envelope.ok(graphService.getGraphData());
    }

    @GetMapping("/anomalies")
    public Envelope<List<AnomalyFinding>> anomalies(
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to) {
        Instant effectiveTo = to != null ? to : timeService.now();
        Instant effectiveFrom = from != null ? from : effectiveTo.minusSeconds(24 * 3600L);
        return Envelope.ok(anomalyRepository.getFindings(effectiveFrom, effectiveTo));
    }

    @GetMapping("/anomaly-incidents")
    public Envelope<List<AnomalyIncident>> anomalyIncidents() {
        return Envelope.ok(anomalyRepository.getIncidents());
    }

    /**
     * Projects a graph at a requested point using the active context's virtual
     * clock and disables cleanup, preserving replay state while ensuring callers
     * cannot inspect future live or simulation positions.
     */
    @GetMapping("/system/snapshot")
    public Envelope<GraphData> snapshotAt(@RequestParam Instant at) {
        Instant current = timeService.now();
        if (at.isAfter(current)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "snapshot time must not be after the active context clock");
        }
        return Envelope.ok(graphService.getGraphData(at, false, DatabaseContextHolder.getSimulationId(), true));
    }

    /**
     * Exposes only persisted simulation metadata; runtime state remains isolated
     * in its own Redis and OrientDB namespaces until a caller explicitly scopes a
     * later semantic operation with that simulation id.
     */
    @GetMapping("/simulations")
    public Envelope<List<SimulationSummary>> simulations() {
        return Envelope.ok(liveSimulationRepository.getAllSimulationStates().stream()
                .map(state -> new SimulationSummary(state.simulationId(), state.timestamp(), state.status().name(),
                        state.lastProcessedTimestamp(), state.speedFactor(), state.buildProgress()))
                .toList());
    }

    @GetMapping("/alarms")
    public CompletableFuture<Envelope<List<AlarmHistoryRecord>>> alarms(
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to) {
        Window window = window(from, to, 60);
        return clickHouseService.getAlarmHistory(null, DatabaseContextHolder.getSimulationId(), window.from(), window.to())
                .thenApply(Envelope::ok);
    }

    @GetMapping("/alarms/{alarmId}/investigation")
    public CompletableFuture<Envelope<AlarmInvestigation>> investigation(
            @PathVariable String alarmId,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "60") int bucketSeconds) {
        Window window = window(from, to, bucketSeconds);
        String simulationId = DatabaseContextHolder.getSimulationId();
        CompletableFuture<List<AlarmHistoryRecord>> history = clickHouseService.getAlarmHistory(
                alarmId, simulationId, window.from(), window.to());
        CompletableFuture<List<String>> affected = clickHouseService.getAlarmAffectedItems(alarmId, simulationId);
        CompletableFuture<List<ThroughputMetric>> throughput = simulationId == null
                ? clickHouseService.getThroughputHistory(window.from(), window.to(), bucketSeconds)
                : throughputBucketService.getSimulationHistory(simulationId, window.from(), window.to(), bucketSeconds);
        return CompletableFuture.allOf(history, affected, throughput).thenApply(ignored -> Envelope.ok(
                new AlarmInvestigation(alarmId, history.join(), affected.join(), throughput.join(),
                        "Typology is reported evidence; the available facts do not independently establish root cause.")));
    }

    @GetMapping("/alarms/{alarmId}/affected-items")
    public CompletableFuture<Envelope<List<String>>> affectedItems(@PathVariable String alarmId) {
        return clickHouseService.getAlarmAffectedItems(alarmId, DatabaseContextHolder.getSimulationId())
                .thenApply(Envelope::ok);
    }

    @GetMapping("/alarms/{alarmId}/reroute-options")
    public CompletableFuture<Envelope<RerouteAssessment>> rerouteOptions(@PathVariable String alarmId) {
        String simulationId = DatabaseContextHolder.getSimulationId();
        var historyFuture = clickHouseService.getAlarmHistory(alarmId, simulationId, Instant.EPOCH, timeService.now());
        var affectedFuture = clickHouseService.getAlarmAffectedItems(alarmId, simulationId);
        return historyFuture.thenCombine(affectedFuture, (history, affectedItems) -> {
                    String conveyorId = history.stream().findFirst().map(AlarmHistoryRecord::conveyorId)
                            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Alarm not found"));
                    List<String> committed = affectedItems.stream()
                            .filter(itemId -> {
                                RedisLiveItem item = liveItemRepository.getItemState(itemId);
                                return item != null && conveyorId.equals(item.getPositionId());
                            }).toList();
                    List<String> upstream = affectedItems.stream()
                            .filter(itemId -> !committed.contains(itemId))
                            .filter(itemId -> liveItemRepository.getItemState(itemId) != null)
                            .toList();
                    List<RerouteOption> options = upstream.stream()
                            .map(this::rerouteOption)
                            .filter(Objects::nonNull)
                            .filter(option -> !conveyorId.equals(option.conveyorId()))
                            .toList();
                    boolean preservesDestination = !upstream.isEmpty() && options.size() == upstream.size();
                    return Envelope.ok(new RerouteAssessment(
                            options, upstream, committed, preservesDestination, true));
                });
    }

    @GetMapping("/items/{itemId}/summary")
    public Envelope<ItemSummary> itemSummary(@PathVariable String itemId) {
        RedisLiveItem item = requireItem(itemId);
        return Envelope.ok(new ItemSummary(item.getId(), item.getName(), item.getPositionId(),
                item.getType() != null ? item.getType().name() : null, item.getDestinations(),
                item.getSelectedExitId(), item.getPath()));
    }

    @GetMapping("/items/{itemId}/events")
    public CompletableFuture<Envelope<List<EntityEventRecord>>> itemEvents(
            @PathVariable String itemId,
            @RequestParam(defaultValue = "200") int limit) {
        if (DatabaseContextHolder.getSimulationId() != null) {
            // Simulation state is reconstructed from live history plus projected
            // events, but projected item events are not persisted as live facts.
            // Returning live Events here would mix scopes and could expose facts
            // after the simulation's historical cutoff.
            return CompletableFuture.completedFuture(Envelope.ok(List.of()));
        }
        return clickHouseService.getEntityEvents(EntityEventType.ITEM, itemId, Math.max(1, Math.min(1000, limit)))
                .thenApply(Envelope::ok);
    }

    @GetMapping("/items/positions")
    public Envelope<List<ItemSummary>> itemPositions() {
        List<ItemSummary> items = liveItemRepository.getAllActiveItems().stream()
                .filter(Objects::nonNull)
                .map(item -> new ItemSummary(item.getId(), item.getName(), item.getPositionId(),
                        item.getType() != null ? item.getType().name() : null, item.getDestinations(),
                        item.getSelectedExitId(), item.getPath()))
                .toList();
        return Envelope.ok(items);
    }

    @GetMapping("/components/{componentId}/summary")
    public Envelope<ComponentSummary> componentSummary(@PathVariable String componentId) {
        Conveyor conveyor = conveyorService.getAllConveyors().stream()
                .filter(candidate -> candidate.getId().equals(componentId)).findFirst().orElse(null);
        if (conveyor != null) {
            return Envelope.ok(new ComponentSummary(componentId, "CONVEYOR", conveyor.isActive(),
                    occupancy(componentId), conveyor.getCapacity(), conveyor.getSpeed(), conveyor.getActiveAlarms()));
        }
        var location = graphService.getGraphData().getLocations().stream()
                .filter(candidate -> candidate.getId().equals(componentId)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Component not found"));
        int occupancy = (int) liveItemRepository.getAllActiveItems().stream()
                .filter(item -> componentId.equals(item.getPositionId())).count();
        return Envelope.ok(new ComponentSummary(componentId, "LOCATION", Boolean.TRUE.equals(location.getActive()),
                occupancy, location.getCapacity(), null, List.of()));
    }

    @GetMapping("/conveyors/flow")
    public Envelope<List<ConveyorFlow>> conveyorFlow() {
        return Envelope.ok(conveyorService.getAllConveyors().stream()
                .map(conveyor -> new ConveyorFlow(conveyor.getId(), conveyor.getSourceLocationId(),
                        conveyor.getTargetLocationId(), conveyor.isActive(), conveyor.isOperatorEnabled(),
                        occupancy(conveyor.getId()), conveyor.getCapacity()))
                .toList());
    }

    @GetMapping("/destinations/{destinationId}/summary")
    public Envelope<DestinationSummary> destinationSummary(@PathVariable String destinationId) {
        List<RedisLiveItem> items = liveItemRepository.getAllActiveItems();
        long assigned = items.stream().filter(item -> destinationId.equals(item.getSelectedExitId())).count();
        long candidates = items.stream().filter(item -> item.getDestinations() != null
                && item.getDestinations().contains(destinationId)).count();
        return Envelope.ok(new DestinationSummary(destinationId, assigned, candidates));
    }

    private RedisLiveItem requireItem(String itemId) {
        RedisLiveItem item = liveItemRepository.getItemState(itemId);
        if (item == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Item not found");
        }
        return item;
    }

    private int occupancy(String conveyorId) {
        return (int) liveItemRepository.getAllActiveItems().stream()
                .filter(item -> conveyorId.equals(item.getPositionId())).count();
    }

    private int availableCapacity(Conveyor conveyor) {
        return conveyor.getCapacity() == null ? Integer.MAX_VALUE
                : Math.max(0, conveyor.getCapacity() - occupancy(conveyor.getId()));
    }

    private RerouteOption rerouteOption(String itemId) {
        RedisLiveItem state = liveItemRepository.getItemState(itemId);
        var item = itemService.getItemById(itemId);
        if (state == null || item == null || state.getSelectedExitId() == null
                || state.getPositionId() == null || state.getType() == null) {
            return null;
        }
        var decision = routingDecisionService.selectRouteToExit(
                item, state.getPositionId(), state.getType(), state.getSelectedExitId());
        if (decision.nextConveyorId() == null || decision.path() == null) {
            return null;
        }
        Conveyor next = conveyorService.getConveyorById(decision.nextConveyorId());
        return new RerouteOption(itemId, next.getId(), decision.selectedExitId(),
                decision.path(), availableCapacity(next), true);
    }

    private Window window(Instant from, Instant to, int bucketSeconds) {
        if (!SUPPORTED_BUCKETS.contains(bucketSeconds)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "bucketSeconds must be one of 60, 300, 900, or 3600");
        }
        Instant effectiveTo = to != null ? to : timeService.now();
        Instant effectiveFrom = from != null ? from : effectiveTo.minusSeconds(3600);
        if (effectiveFrom.isAfter(effectiveTo)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "from must be before to");
        }
        return new Window(effectiveFrom, effectiveTo);
    }

    private record Window(Instant from, Instant to) {
    }

    private long sumEntered(List<ThroughputMetric> metrics) {
        return metrics.stream().mapToLong(ThroughputMetric::getItemsEntered).sum();
    }

    private long sumExited(List<ThroughputMetric> metrics) {
        return metrics.stream().mapToLong(ThroughputMetric::getItemsExited).sum();
    }
}
