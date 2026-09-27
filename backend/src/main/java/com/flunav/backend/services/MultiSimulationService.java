package com.flunav.backend.services;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flunav.backend.context.DatabaseContextHolder;
import com.flunav.backend.models.graph.GraphData;
import com.flunav.backend.models.multisimulation.ArrivalConfiguration;
import com.flunav.backend.models.multisimulation.ConveyorFailureConfiguration;
import com.flunav.backend.models.multisimulation.DestinationProbability;
import com.flunav.backend.models.multisimulation.MultiSimulation;
import com.flunav.backend.models.multisimulation.MultiSimulationBaseline;
import com.flunav.backend.models.multisimulation.MultiSimulationConfiguration;
import com.flunav.backend.models.multisimulation.MultiSimulationReport;
import com.flunav.backend.models.multisimulation.MultiSimulationRun;
import com.flunav.backend.models.multisimulation.MultiSimulationStatus;
import com.flunav.backend.models.response.ConveyorResponse;
import com.flunav.backend.models.response.LocationResponse;
import com.flunav.backend.models.response.MultiSimulationEstimateResponse;

import flunav.events.DestinationExitMappingRecord;
import flunav.events.DomainEvent;
import flunav.types.LocationType;

@Service
public class MultiSimulationService {
    private static final double PROBABILITY_TOLERANCE = 1.0e-9;
    private static final long MAX_SAFE_CLIENT_SEED = 9_007_199_254_740_991L;
    private static final double BENCHMARK_CONVEYOR_COUNT = 15.0;
    private static final double BENCHMARK_ROUTE_DEPTH = 5.0;
    private static final double BENCHMARK_REACHABLE_EXIT_COUNT = 5.0;

    private final ClickHouseService clickHouseService;
    private final EventProcessor eventProcessor;
    private final GraphService graphService;
    private final DestinationExitMappingService destinationExitMappingService;
    private final MultiSimulationRunner runner;
    private final SimulationService simulationService;
    private final TimeService timeService;
    private final WebSocketService webSocketService;
    private final ObjectMapper objectMapper;
    private final SecureRandom secureRandom = new SecureRandom();
    private final int maximumRuns;

    public MultiSimulationService(
            ClickHouseService clickHouseService,
            EventProcessor eventProcessor,
            GraphService graphService,
            DestinationExitMappingService destinationExitMappingService,
            MultiSimulationRunner runner,
            SimulationService simulationService,
            TimeService timeService,
            WebSocketService webSocketService,
            ObjectMapper objectMapper,
            @Value("${multi-simulation.max-runs:1000}") int maximumRuns) {
        this.clickHouseService = clickHouseService;
        this.eventProcessor = eventProcessor;
        this.graphService = graphService;
        this.destinationExitMappingService = destinationExitMappingService;
        this.runner = runner;
        this.simulationService = simulationService;
        this.timeService = timeService;
        this.webSocketService = webSocketService;
        this.objectMapper = objectMapper;
        this.maximumRuns = maximumRuns;
    }

    /** Captures one immutable topology/configuration baseline for every run. */
    public MultiSimulation create(MultiSimulationConfiguration request) {
        Instant createdAt = timeService.physicalNow();
        long seed = request.baseSeed() != null ? request.baseSeed() : safeGeneratedSeed(request.numberOfRuns());
        if (seed < -MAX_SAFE_CLIENT_SEED || seed > MAX_SAFE_CLIENT_SEED) {
            throw badRequest("baseSeed must be a JSON-safe integer");
        }
        if (request.numberOfRuns() > 0 && seed > Long.MAX_VALUE - (request.numberOfRuns() - 1L)) {
            throw badRequest("baseSeed is too large for the requested number of runs");
        }
        String sourceSimulationId = DatabaseContextHolder.getSimulationId();
        Instant sourceClock = sourceSimulationId == null
                ? createdAt
                : simulationService.getSimulationClock(sourceSimulationId);
        Instant simulationStart = request.simulationStartTime() != null ? request.simulationStartTime() : sourceClock;
        MultiSimulationConfiguration configuration = new MultiSimulationConfiguration(
                request.name() == null ? null : request.name().trim(),
                request.simulationDurationSeconds(),
                request.numberOfRuns(),
                request.arrival(),
                request.sourceLocationId() == null ? null : request.sourceLocationId().trim(),
                request.destinations(),
                request.conveyorFailures(),
                seed,
                simulationStart,
                request.includeActiveItems());

        MultiSimulationBaseline baseline = eventProcessor.withLiveSnapshotBarrier(() -> {
            clickHouseService.flushAllEventsOrThrow();
            GraphData current = request.includeActiveItems()
                    ? graphService.getGraphData(simulationStart, false, sourceSimulationId, true)
                    : graphService.getTopologyData();
            GraphData topologyOnly = new GraphData(
                    current.getLocations(),
                    current.getConveyors(),
                    request.includeActiveItems() ? current.getItems() : List.of(),
                    current.getSensorMappings(),
                    simulationStart);
            validate(configuration, topologyOnly);
            List<DomainEvent> configurationEvents = clickHouseService.getLatestConfigurationEventsBefore(createdAt);
            return new MultiSimulationBaseline(
                    topologyOnly,
                    configurationEvents,
                    createdAt,
                    hash(Map.of("locations", topologyOnly.getLocations(), "conveyors", topologyOnly.getConveyors())),
                    hash(configurationEvents));
        });

        String id = "multi_" + UUID.randomUUID().toString().replace("-", "");
        MultiSimulation simulation = new MultiSimulation(
                id, configuration, baseline, seed, MultiSimulationStatus.DRAFT,
                0, 0, createdAt, null, null, false, null);
        clickHouseService.saveMultiSimulation(simulation);
        return simulation;
    }

    public MultiSimulation start(String id) {
        MultiSimulation current = get(id);
        if (current.status() != MultiSimulationStatus.DRAFT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Multi-simulation has already been started");
        }
        MultiSimulation queued = copy(current, MultiSimulationStatus.QUEUED, 0, 0, null, null, false, null);
        clickHouseService.saveMultiSimulation(queued);
        webSocketService.broadcastMultiSimulationUpdate(queued, timeService.physicalNow());
        runner.run(id);
        return queued;
    }

    public MultiSimulation cancel(String id) {
        MultiSimulation current = get(id);
        if (isTerminal(current.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Multi-simulation is already finished");
        }
        MultiSimulation cancelling = copy(current, MultiSimulationStatus.CANCELLING,
                current.completedRuns(), current.failedRuns(), current.startedAt(), current.completedAt(), true, null);
        clickHouseService.saveMultiSimulation(cancelling);
        webSocketService.broadcastMultiSimulationUpdate(cancelling, timeService.physicalNow());
        runner.requestCancellation(id);
        return cancelling;
    }

    public MultiSimulation get(String id) {
        return clickHouseService.getMultiSimulation(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Multi-simulation not found"));
    }

    public List<MultiSimulation> list() {
        return clickHouseService.getMultiSimulations();
    }

    /** Estimates wall time from the benchmark workload and the active route topology. */
    public MultiSimulationEstimateResponse estimate(MultiSimulationConfiguration request) {
        if (request == null || request.numberOfRuns() <= 0 || request.numberOfRuns() > maximumRuns
                || request.simulationDurationSeconds() <= 0 || request.arrival() == null
                || !Double.isFinite(request.arrival().ratePerHour()) || request.arrival().ratePerHour() <= 0) {
            throw badRequest("Valid runs, duration, and arrival rate are required for an estimate");
        }
        double expectedItems = request.arrival().ratePerHour()
                * (request.simulationDurationSeconds() / 3600.0);
        if (!Double.isFinite(expectedItems) || expectedItems > Long.MAX_VALUE / (double) request.numberOfRuns()) {
            throw badRequest("Expected item count is too large to estimate");
        }
        GraphData graph = graphService.getTopologyData();
        int locations = graph.getLocations() == null ? 0 : graph.getLocations().size();
        TopologyProfile topology = profileTopology(request.sourceLocationId(), graph);
        int parallelRuns = Math.min(request.numberOfRuns(), runner.maximumConcurrentRuns());
        double graphFactor = Math.pow(Math.max(1, topology.activeConveyorCount()) / BENCHMARK_CONVEYOR_COUNT, 0.5)
                * Math.pow(Math.max(1, topology.maximumRouteDepth()) / BENCHMARK_ROUTE_DEPTH, 0.5)
                * Math.pow(Math.max(1, topology.reachableExitCount()) / BENCHMARK_REACHABLE_EXIT_COUNT, 0.2);
        double workerFactor = Math.pow(parallelRuns / 20.0, 0.474);
        double itemWork = 0.883 * (expectedItems / 2408.0) * graphFactor;
        double seconds = 0.5 + Math.ceil(request.numberOfRuns() / (double) parallelRuns)
                * (0.1 + itemWork) * workerFactor;
        long estimate = Math.max(1L, Math.round(seconds));
        return new MultiSimulationEstimateResponse(
                Math.round(expectedItems), Math.round(expectedItems * request.numberOfRuns()),
                locations, topology.activeConveyorCount(), topology.reachableExitCount(), topology.maximumRouteDepth(),
                runner.maximumConcurrentRuns(), parallelRuns, estimate,
                Math.max(1L, Math.round(seconds * 0.75)), Math.max(1L, Math.round(seconds * 1.5)));
    }

    public List<MultiSimulationRun> runs(String id) {
        get(id);
        return clickHouseService.getMultiSimulationRuns(id);
    }

    public MultiSimulationReport report(String id) {
        get(id);
        return clickHouseService.getMultiSimulationReport(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Report is not ready"));
    }

    private void validate(MultiSimulationConfiguration configuration, GraphData topology) {
        if (configuration.name() == null || configuration.name().isBlank()) {
            throw badRequest("name is required");
        }
        if (configuration.simulationDurationSeconds() <= 0) {
            throw badRequest("simulationDurationSeconds must be greater than zero");
        }
        if (configuration.numberOfRuns() <= 0 || configuration.numberOfRuns() > maximumRuns) {
            throw badRequest("numberOfRuns must be between 1 and " + maximumRuns);
        }
        ArrivalConfiguration arrival = configuration.arrival();
        if (arrival == null || arrival.distribution() == null || !Double.isFinite(arrival.ratePerHour())
                || arrival.ratePerHour() <= 0) {
            throw badRequest("a positive arrival rate and distribution are required");
        }
        if (!Double.isFinite(arrival.rateVariationPercent()) || arrival.rateVariationPercent() < 0
                || arrival.rateVariationPercent() >= 100) {
            throw badRequest("rateVariationPercent must be between 0 inclusive and 100 exclusive");
        }
        if (configuration.destinations().isEmpty()) {
            throw badRequest("at least one destination is required");
        }
        double probability = configuration.destinations().stream().mapToDouble(DestinationProbability::probability).sum();
        if (configuration.destinations().stream().anyMatch(value -> value.destination() == null
                || value.destination().isBlank() || !Double.isFinite(value.probability()) || value.probability() <= 0)
                || Math.abs(probability - 1.0) > PROBABILITY_TOLERANCE) {
            throw badRequest("destination probabilities must be positive and sum to 1");
        }
        Set<String> destinationNames = new HashSet<>();
        if (configuration.destinations().stream().anyMatch(value -> !destinationNames.add(value.destination()))) {
            throw badRequest("destination names must be unique");
        }

        Map<String, LocationResponse> locations = topology.getLocations().stream()
                .collect(Collectors.toMap(LocationResponse::getId, Function.identity()));
        LocationResponse source = locations.get(configuration.sourceLocationId());
        if (source == null || !Boolean.TRUE.equals(source.getActive())) {
            throw badRequest("sourceLocationId must reference an active location");
        }
        Map<String, DestinationExitMappingRecord> exitMappings = destinationExitMappingService.getMappings().stream()
                .collect(Collectors.toMap(DestinationExitMappingRecord::getDestination, Function.identity()));
        Set<String> reachableLocations = reachableLocations(configuration.sourceLocationId(), topology.getConveyors());
        for (DestinationProbability destination : configuration.destinations()) {
            DestinationExitMappingRecord mapping = exitMappings.get(destination.destination());
            if (mapping == null) {
                LocationResponse directExit = locations.get(destination.destination());
                if (directExit == null || directExit.getType() != LocationType.CHUTE
                        || !Boolean.TRUE.equals(directExit.getActive())) {
                    throw badRequest("destination has no valid configured exit: " + destination.destination());
                }
                if (!reachableLocations.contains(destination.destination())) {
                    throw badRequest("destination has no exit reachable from the source: " + destination.destination());
                }
                continue;
            }
            if (mapping.getExits().isEmpty()
                    || mapping.getExits().stream().anyMatch(exit -> !locations.containsKey(exit))) {
                throw badRequest("destination has no valid configured exit: " + destination.destination());
            }
            if (mapping.getExits().stream().noneMatch(reachableLocations::contains)) {
                throw badRequest("destination has no exit reachable from the source: " + destination.destination());
            }
        }

        Map<String, ConveyorResponse> conveyors = topology.getConveyors().stream()
                .collect(Collectors.toMap(ConveyorResponse::getId, Function.identity()));
        Set<String> configuredFailures = new HashSet<>();
        for (ConveyorFailureConfiguration failure : configuration.conveyorFailures()) {
            if (failure == null || failure.conveyorId() == null || !conveyors.containsKey(failure.conveyorId())) {
                throw badRequest("conveyor failure configuration references an unknown conveyor");
            }
            if (!configuredFailures.add(failure.conveyorId())) {
                throw badRequest("a conveyor may have only one failure configuration");
            }
            if (!Double.isFinite(failure.failuresPerHour()) || failure.failuresPerHour() <= 0) {
                throw badRequest("failuresPerHour must be greater than zero");
            }
            if (failure.repairDurationSeconds() != null && failure.repairDurationSeconds() <= 0) {
                throw badRequest("repairDurationSeconds must be greater than zero when provided");
            }
        }
    }

    private Set<String> reachableLocations(String sourceId, List<ConveyorResponse> conveyors) {
        Map<String, Set<String>> outgoing = new HashMap<>();
        for (ConveyorResponse conveyor : conveyors) {
            if (Boolean.TRUE.equals(conveyor.getActive())) {
                outgoing.computeIfAbsent(conveyor.getSourceId(), ignored -> new HashSet<>())
                        .add(conveyor.getTargetId());
            }
        }
        Set<String> visited = new HashSet<>();
        ArrayDeque<String> pending = new ArrayDeque<>();
        visited.add(sourceId);
        pending.add(sourceId);
        while (!pending.isEmpty()) {
            for (String target : outgoing.getOrDefault(pending.removeFirst(), Set.of())) {
                if (visited.add(target)) {
                    pending.addLast(target);
                }
            }
        }
        return visited;
    }

    /** Profiles active directed routes so cycles do not increase the longest shortest route. */
    private TopologyProfile profileTopology(String sourceId, GraphData graph) {
        List<ConveyorResponse> conveyors = graph.getConveyors() == null ? List.of() : graph.getConveyors();
        int activeConveyorCount = 0;
        Map<String, Set<String>> outgoing = new HashMap<>();
        for (ConveyorResponse conveyor : conveyors) {
            if (Boolean.TRUE.equals(conveyor.getActive())) {
                activeConveyorCount++;
                outgoing.computeIfAbsent(conveyor.getSourceId(), ignored -> new HashSet<>()).add(conveyor.getTargetId());
            }
        }
        if (sourceId == null || sourceId.isBlank()) {
            return new TopologyProfile(activeConveyorCount, 0, 0);
        }

        Map<String, Integer> distances = new HashMap<>();
        ArrayDeque<String> pending = new ArrayDeque<>();
        distances.put(sourceId, 0);
        pending.add(sourceId);
        while (!pending.isEmpty()) {
            String locationId = pending.removeFirst();
            int nextDistance = distances.get(locationId) + 1;
            for (String target : outgoing.getOrDefault(locationId, Set.of())) {
                if (!distances.containsKey(target)) {
                    distances.put(target, nextDistance);
                    pending.addLast(target);
                }
            }
        }

        Set<String> activeChutes = (graph.getLocations() == null ? List.<LocationResponse>of() : graph.getLocations())
                .stream()
                .filter(location -> location.getType() == LocationType.CHUTE && Boolean.TRUE.equals(location.getActive()))
                .map(LocationResponse::getId)
                .collect(Collectors.toSet());
        int reachableExitCount = 0;
        int maximumRouteDepth = 0;
        for (Map.Entry<String, Integer> entry : distances.entrySet()) {
            if (activeChutes.contains(entry.getKey())) {
                reachableExitCount++;
                maximumRouteDepth = Math.max(maximumRouteDepth, entry.getValue());
            }
        }
        return new TopologyProfile(activeConveyorCount, reachableExitCount, maximumRouteDepth);
    }

    private record TopologyProfile(int activeConveyorCount, int reachableExitCount, int maximumRouteDepth) {
    }

    private String hash(Object value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(objectMapper.writeValueAsString(value).getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to version multi-simulation baseline", e);
        }
    }

    private long safeGeneratedSeed(int numberOfRuns) {
        long maximumExclusive = MAX_SAFE_CLIENT_SEED - Math.max(0L, numberOfRuns - 1L);
        return secureRandom.nextLong(maximumExclusive);
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private boolean isTerminal(MultiSimulationStatus status) {
        return status == MultiSimulationStatus.COMPLETED
                || status == MultiSimulationStatus.COMPLETED_WITH_FAILURES
                || status == MultiSimulationStatus.CANCELLED
                || status == MultiSimulationStatus.FAILED;
    }

    private MultiSimulation copy(
            MultiSimulation source,
            MultiSimulationStatus status,
            int completedRuns,
            int failedRuns,
            Instant startedAt,
            Instant completedAt,
            boolean cancelRequested,
            String error) {
        return new MultiSimulation(source.id(), source.configuration(), source.baseline(), source.baseSeed(), status,
                completedRuns, failedRuns, source.createdAt(), startedAt, completedAt, cancelRequested, error);
    }
}
