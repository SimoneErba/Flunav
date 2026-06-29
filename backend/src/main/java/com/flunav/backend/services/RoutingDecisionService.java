package com.flunav.backend.services;

import com.flunav.backend.domain.Conveyor;
import com.flunav.backend.domain.Item;
import com.flunav.backend.domain.Location;
import com.flunav.backend.repositories.LiveItemRepository;
import com.flunav.backend.repositories.LiveLocationRepository;
import com.flunav.backend.repositories.PathCacheRepository;
import flunav.types.LocationType;
import flunav.types.PositionType;
import flunav.types.RoutingStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;

@Service
public class RoutingDecisionService {
    private static final double NORMAL_PRIORITY_CAPACITY_LIMIT = 0.90;
    private static final double PENDING_RESERVATION_FRACTION = 0.20;

    private final TopologyProvider topologyProvider;
    private final DestinationExitMappingService destinationExitMappingService;
    private final LiveItemRepository liveItemRepository;
    private final LiveLocationRepository liveLocationRepository;
    private final ItemService itemService;
    private final PathCacheRepository pathCacheRepository;

    public RoutingDecisionService(
            TopologyProvider topologyProvider,
            DestinationExitMappingService destinationExitMappingService,
            LiveItemRepository liveItemRepository,
            LiveLocationRepository liveLocationRepository,
            ItemService itemService,
            PathCacheRepository pathCacheRepository) {
        this.topologyProvider = topologyProvider;
        this.destinationExitMappingService = destinationExitMappingService;
        this.liveItemRepository = liveItemRepository;
        this.liveLocationRepository = liveLocationRepository;
        this.itemService = itemService;
        this.pathCacheRepository = pathCacheRepository;
    }

    /**
     * Chooses an exit and path for a newly routable item.
     * Destination mappings define the candidate exits, capacity rules decide
     * whether an exit can be reserved, and priority controls the tradeoff between
     * low utilization and shortest travel time.
     */
    public RoutingDecision selectRoute(Item item, String sourceId, PositionType sourceType) {
        if (item == null || sourceId == null) {
            return RoutingDecision.none();
        }

        String sourceLocationId = resolveSourceLocation(sourceId, sourceType);
        if (sourceLocationId == null) {
            return RoutingDecision.none();
        }

        double priorityScore = priorityScore(item);
        Map<String, Integer> pendingReservations = allocatePendingReservations(item.getId());
        List<RouteCandidate> candidates = new ArrayList<>();
        boolean capacityBlocked = false;
        Set<String> candidateExits = resolveCandidateExits(item.getDestinations());
        if (!normalizeDestinations(item.getDestinations()).isEmpty() && candidateExits.isEmpty()) {
            return new RoutingDecision(null, null, null, false, RoutingStatus.FAILED, false);
        }

        for (String exitId : candidateExits) {
            Location exit = findLocation(exitId);
            if (exit == null) {
                continue;
            }

            PathResult path = calculateAvailablePath(sourceLocationId, exitId);
            if (path == null) {
                continue;
            }

            CapacityState capacity = capacityState(exit, item.getId(), pendingReservations);
            if (capacity.canAccept(priorityScore)) {
                candidates.add(new RouteCandidate(exitId, path, capacity.utilization()));
            } else if (capacity.isCapacityLimited(priorityScore)) {
                capacityBlocked = true;
            }
        }

        if (candidates.isEmpty() && !candidateExits.isEmpty() && !capacityBlocked) {
            return new RoutingDecision(null, null, null, false, RoutingStatus.FAILED, false);
        }

        Comparator<RouteCandidate> comparator = routeComparator(candidates, priorityScore);

        RoutingDecision assigned = candidates.stream()
                .min(comparator)
                .map(candidate -> new RoutingDecision(
                        candidate.exitId(),
                        candidate.path().locations(),
                        firstConveyor(candidate.path().locations()),
                        true,
                        RoutingStatus.ASSIGNED,
                        false))
                .orElse(null);
        if (assigned != null) {
            return assigned;
        }

        if (capacityBlocked) {
            RoutingDecision fallback = fallbackDecision(sourceLocationId);
            return new RoutingDecision(
                    null,
                    fallback.path(),
                    fallback.nextConveyorId(),
                    false,
                    canWaitForCapacity(priorityScore) ? RoutingStatus.WAITING_FOR_CAPACITY : RoutingStatus.UNROUTED,
                    true);
        }
        return fallbackDecision(sourceLocationId);
    }

    /**
     * Revalidates a specific selected exit before committing movement toward it.
     * This keeps stale assignments from driving an item into a full chute after
     * occupancy or reservations have changed.
     */
    public RoutingDecision selectRouteToExit(
            Item item,
            String sourceId,
            PositionType sourceType,
            String exitId) {
        if (item == null || exitId == null) {
            return RoutingDecision.none();
        }

        String sourceLocationId = resolveSourceLocation(sourceId, sourceType);
        Location exit = findLocation(exitId);
        if (sourceLocationId == null || exit == null) {
            return sourceLocationId == null ? RoutingDecision.none() : failedDecision();
        }
        double priorityScore = priorityScore(item);
        CapacityState capacity = capacityState(exit, item.getId(), allocatePendingReservations(item.getId()));
        if (!capacity.canAccept(priorityScore)) {
            RoutingDecision fallback = fallbackDecision(sourceLocationId);
            return new RoutingDecision(
                    null,
                    fallback.path(),
                    fallback.nextConveyorId(),
                    false,
                    canWaitForCapacity(priorityScore) ? RoutingStatus.WAITING_FOR_CAPACITY : RoutingStatus.UNROUTED,
                    true);
        }

        PathResult path = calculateAvailablePath(sourceLocationId, exitId);
        if (path == null) {
            return failedDecision();
        }
        return new RoutingDecision(exitId, path.locations(), firstConveyor(path.locations()), true,
                RoutingStatus.ASSIGNED, false);
    }

    /**
     * Resolves business destinations to physical exits while preserving mapping
     * order. Direct location ids remain valid so callers can route without a
     * destination mapping when the destination is already a chute/location.
     */
    private Set<String> resolveCandidateExits(List<String> destinations) {
        if (destinations == null || destinations.isEmpty()) {
            return Set.of();
        }

        LinkedHashSet<String> exits = new LinkedHashSet<>();
        for (String destination : destinations) {
            List<String> mappedExits = destinationExitMappingService.getExits(destination);
            if (mappedExits.isEmpty()) {
                Location directLocation = findLocation(destination);
                if (directLocation != null) {
                    exits.add(destination);
                }
            } else {
                exits.addAll(mappedExits);
            }
        }
        return exits;
    }

    /**
     * Runs shortest-path search only across usable conveyors.
     * Stopped or inactive conveyors are excluded here so routing decisions do not
     * schedule items onto paths that movement processing cannot advance.
     */
    private PathResult calculateAvailablePath(String sourceLocationId, String exitId) {
        var cached = pathCacheRepository.getAvailablePath(sourceLocationId, exitId);
        if (cached.isPresent()) {
            PathCacheRepository.AvailablePath path = cached.get();
            return new PathResult(path.locations(), path.travelSeconds());
        }

        Map<String, List<Conveyor>> outgoing = new HashMap<>();
        for (Conveyor conveyor : topologyProvider.getAllConveyors()) {
            if (isAvailable(conveyor)) {
                outgoing.computeIfAbsent(conveyor.getSourceLocationId(), ignored -> new ArrayList<>()).add(conveyor);
            }
        }

        PriorityQueue<PathNode> queue = new PriorityQueue<>(Comparator.comparingDouble(PathNode::distance));
        Map<String, Double> distances = new HashMap<>();
        Map<String, String> previous = new HashMap<>();
        Set<String> visited = new HashSet<>();

        distances.put(sourceLocationId, 0.0);
        queue.add(new PathNode(sourceLocationId, 0.0));

        while (!queue.isEmpty()) {
            PathNode current = queue.poll();
            if (!visited.add(current.locationId())) {
                continue;
            }
            if (current.locationId().equals(exitId)) {
                PathResult result = new PathResult(buildPath(previous, sourceLocationId, exitId), current.distance());
                pathCacheRepository.putAvailablePath(sourceLocationId, exitId, result.locations(),
                        result.travelSeconds());
                return result;
            }

            for (Conveyor conveyor : outgoing.getOrDefault(current.locationId(), List.of())) {
                double nextDistance = current.distance() + travelSeconds(conveyor);
                if (nextDistance < distances.getOrDefault(conveyor.getTargetLocationId(), Double.POSITIVE_INFINITY)) {
                    distances.put(conveyor.getTargetLocationId(), nextDistance);
                    previous.put(conveyor.getTargetLocationId(), current.locationId());
                    queue.add(new PathNode(conveyor.getTargetLocationId(), nextDistance));
                }
            }
        }
        return null;
    }

    /**
     * Reconstructs the location path produced by the shortest-path search.
     * The returned list is copied so callers can persist it as an immutable routing
     * assignment in Redis.
     */
    private List<String> buildPath(Map<String, String> previous, String sourceId, String exitId) {
        List<String> reversed = new ArrayList<>();
        String current = exitId;
        while (current != null) {
            reversed.add(current);
            if (current.equals(sourceId)) {
                break;
            }
            current = previous.get(current);
        }
        java.util.Collections.reverse(reversed);
        return List.copyOf(reversed);
    }

    /**
     * Chooses a non-chute outgoing conveyor when no exit can be assigned.
     * Recirculating on the main path keeps normal flow moving without consuming
     * chute capacity reserved for assigned items.
     */
    private RoutingDecision fallbackDecision(String sourceLocationId) {
        Conveyor fallback = topologyProvider.getOutgoingConveyors(sourceLocationId).stream()
                .filter(this::isAvailable)
                .filter(conveyor -> {
                    Location target = findLocation(conveyor.getTargetLocationId());
                    return target == null || target.getType() != LocationType.CHUTE;
                })
                .sorted(Comparator.comparing(Conveyor::isMainPath).reversed())
                .findFirst()
                .orElse(null);

        if (fallback == null) {
            return RoutingDecision.none();
        }
        return new RoutingDecision(
                null,
                List.of(sourceLocationId, fallback.getTargetLocationId()),
                fallback.getId(),
                false,
                RoutingStatus.UNROUTED,
                false);
    }

    private RoutingDecision failedDecision() {
        return new RoutingDecision(null, null, null, false, RoutingStatus.FAILED, false);
    }

    /**
     * Resolves the first conveyor for a chosen location path.
     * Routing stores both the full path and the immediate conveyor so movement
     * scheduling can start without recalculating the path.
     */
    private String firstConveyor(List<String> path) {
        if (path == null || path.size() < 2) {
            return null;
        }
        String nextLocationId = path.get(1);
        return topologyProvider.getOutgoingConveyors(path.getFirst()).stream()
                .filter(this::isAvailable)
                .filter(conveyor -> nextLocationId.equals(conveyor.getTargetLocationId()))
                .map(Conveyor::getId)
                .findFirst()
                .orElse(null);
    }

    /**
     * Normalizes the source of a routing decision to a location id.
     * Items already on conveyors are routed from that conveyor's target location
     * because the current segment is already committed.
     */
    private String resolveSourceLocation(String sourceId, PositionType sourceType) {
        if (sourceType == PositionType.CONVEYOR) {
            Conveyor conveyor = findConveyor(sourceId);
            return conveyor != null ? conveyor.getTargetLocationId() : null;
        }
        return findLocation(sourceId) != null ? sourceId : null;
    }

    /**
     * Checks whether a conveyor can participate in route selection.
     * Routing excludes inactive or stopped conveyors so assignments only use paths
     * movement processing can actually advance.
     */
    private boolean isAvailable(Conveyor conveyor) {
        return conveyor != null
                && conveyor.isActive()
                && conveyor.getSpeed() != null
                && conveyor.getSpeed() > 0;
    }

    /**
     * Looks up a location and converts missing topology into a null candidate.
     * Routing treats missing exits as unusable instead of failing the whole decision
     * when a stale mapping references a removed location.
     */
    private Location findLocation(String locationId) {
        try {
            return topologyProvider.getLocationById(locationId);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /**
     * Looks up a conveyor and converts missing topology into null.
     * This keeps source normalization tolerant of stale Redis positions during
     * recovery or cleanup.
     */
    private Conveyor findConveyor(String conveyorId) {
        try {
            return topologyProvider.getConveyorById(conveyorId);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /**
     * Converts conveyor length and speed into route cost.
     * A small positive floor keeps shortest-path ordering stable for zero-length
     * segments while available conveyors still require positive speed.
     */
    private double travelSeconds(Conveyor conveyor) {
        double length = conveyor.getLength() != null ? conveyor.getLength() : 0.0;
        return Math.max(length / conveyor.getSpeed(), 0.001);
    }

    /**
     * Builds the candidate ordering for priority-aware routing.
     * Full priority minimizes travel time, normal priority protects capacity, and
     * fractional priority blends both pressures into a stable score.
     */
    private Comparator<RouteCandidate> routeComparator(List<RouteCandidate> candidates, double priorityScore) {
        if (priorityScore >= 1.0) {
            return Comparator
                    .comparingDouble((RouteCandidate candidate) -> candidate.path().travelSeconds())
                    .thenComparing(RouteCandidate::exitId);
        }
        if (priorityScore <= 0.0) {
            return Comparator
                    .comparingDouble(RouteCandidate::utilization)
                    .thenComparingDouble(candidate -> candidate.path().travelSeconds())
                    .thenComparing(RouteCandidate::exitId);
        }

        double maxTravelSeconds = candidates.stream()
                .mapToDouble(candidate -> candidate.path().travelSeconds())
                .max()
                .orElse(1.0);
        return Comparator
                .comparingDouble((RouteCandidate candidate) ->
                        routeScore(candidate, priorityScore, maxTravelSeconds))
                .thenComparingDouble(RouteCandidate::utilization)
                .thenComparingDouble(candidate -> candidate.path().travelSeconds())
                .thenComparing(RouteCandidate::exitId);
    }

    /**
     * Blends utilization pressure and travel time into one route score.
     * Higher priority weights travel time more heavily while normal priority favors
     * less occupied exits.
     */
    private double routeScore(RouteCandidate candidate, double priorityScore, double maxTravelSeconds) {
        double travelScore = maxTravelSeconds <= 0.0
                ? 0.0
                : candidate.path().travelSeconds() / maxTravelSeconds;
        return candidate.utilization() * (1.0 - priorityScore) + travelScore * priorityScore;
    }

    /**
     * Counts physical chute occupancy, already assigned items, and reserved pending
     * demand together so a route cannot overcommit capacity before items arrive.
     */
    private CapacityState capacityState(Location exit, String itemId, Map<String, Integer> pendingReservations) {
        if (exit.getType() != LocationType.CHUTE) {
            return new CapacityState(0, null);
        }
        Integer capacity = exit.getCapacity();
        long occupancy = liveLocationRepository.getItemCount(exit.getId())
                + liveItemRepository.countItemsAssignedToExit(exit.getId(), itemId)
                + pendingReservations.getOrDefault(exit.getId(), 0);
        if (capacity == null || capacity <= 0) {
            return new CapacityState(occupancy, null);
        }
        return new CapacityState(occupancy, capacity);
    }

    /**
     * Projects waiting high-priority demand into capacity reservations.
     * Reservations are allocated before evaluating the current item so normal items
     * cannot consume all near-future capacity while urgent items are waiting.
     */
    private Map<String, Integer> allocatePendingReservations(String excludedItemId) {
        List<PendingRoutingDemand> pendingDemands = liveItemRepository.getAllActiveItems().stream()
                .filter(item -> item != null)
                .filter(item -> excludedItemId == null || !excludedItemId.equals(item.getId()))
                .filter(item -> item.getRoutingStatus() == RoutingStatus.WAITING_FOR_CAPACITY)
                .map(item -> {
                    Item domainItem = itemService.getItemById(item.getId());
                    double priorityScore = domainItem == null ? 0.0 : priorityScore(domainItem);
                    if (!canWaitForCapacity(priorityScore)) {
                        return null;
                    }
                    String sourceLocationId = resolveSourceLocation(item.getPositionId(), item.getType());
                    if (sourceLocationId == null) {
                        return null;
                    }
                    List<PendingCandidate> candidates = pendingCandidates(sourceLocationId, domainItem.getDestinations());
                    if (candidates.isEmpty()) {
                        return null;
                    }
                    return new PendingRoutingDemand(
                            item.getId(),
                            priorityScore,
                            item.getRoutingStatusUpdatedAt(),
                            item.getEntryTime(),
                            candidates);
                })
                .filter(Objects::nonNull)
                .sorted(Comparator
                        .comparingDouble(PendingRoutingDemand::priorityScore)
                        .reversed()
                        .thenComparing(PendingRoutingDemand::statusUpdatedAt,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(PendingRoutingDemand::entryTime,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(PendingRoutingDemand::itemId))
                .toList();

        Map<String, Integer> reservations = new HashMap<>();
        for (PendingRoutingDemand demand : pendingDemands) {
            demand.candidates().stream()
                    .filter(candidate -> {
                        int limit = pendingReservationLimit(candidate.capacity(), demand.priorityScore());
                        return limit > 0 && reservations.getOrDefault(candidate.exitId(), 0) < limit;
                    })
                    .min(Comparator
                            .<PendingCandidate>comparingDouble(candidate -> projectedUtilization(candidate, reservations))
                            .thenComparing(PendingCandidate::travelSeconds)
                            .thenComparing(PendingCandidate::exitId))
                    .ifPresent(candidate -> reservations.merge(candidate.exitId(), 1, Integer::sum));
        }
        return reservations;
    }

    /**
     * Builds the chute candidates a waiting item could claim once capacity opens.
     * Only finite-capacity chutes are reserved because unlimited exits do not need
     * protection from pending demand.
     */
    private List<PendingCandidate> pendingCandidates(String sourceLocationId, List<String> destinations) {
        Set<String> candidateExits = resolveCandidateExits(destinations);
        if (candidateExits.isEmpty()) {
            return Collections.emptyList();
        }
        List<PendingCandidate> candidates = new ArrayList<>();
        for (String exitId : candidateExits) {
            Location exit = findLocation(exitId);
            if (exit == null || exit.getType() != LocationType.CHUTE
                    || exit.getCapacity() == null || exit.getCapacity() <= 0) {
                continue;
            }
            PathResult path = calculateAvailablePath(sourceLocationId, exitId);
            if (path == null) {
                continue;
            }
            candidates.add(new PendingCandidate(
                    exitId,
                    exit.getCapacity(),
                    liveLocationRepository.getItemCount(exitId)
                            + liveItemRepository.countItemsAssignedToExit(exitId, null),
                    path.travelSeconds()));
        }
        return candidates;
    }

    /**
     * Caps pending reservations to a priority-weighted slice of chute capacity so
     * high-priority queues are protected without starving normal flow completely.
     */
    private int pendingReservationLimit(int capacity, double priorityScore) {
        return Math.max(0, (int) Math.ceil(capacity * PENDING_RESERVATION_FRACTION * priorityScore));
    }

    /**
     * Projects chute utilization after already allocated pending reservations.
     * Pending demand uses this score to distribute reserved capacity across exits.
     */
    private double projectedUtilization(PendingCandidate candidate, Map<String, Integer> reservations) {
        return (double) (candidate.baseOccupancy() + reservations.getOrDefault(candidate.exitId(), 0))
                / candidate.capacity();
    }

    /**
     * Normalizes destination ids before deciding whether mappings are missing.
     * Blank values are ignored here because creation/update validation handles
     * user-facing errors before routing is called.
     */
    private List<String> normalizeDestinations(List<String> destinations) {
        if (destinations == null || destinations.isEmpty()) {
            return List.of();
        }
        return destinations.stream()
                .filter(destination -> destination != null && !destination.isBlank())
                .map(String::trim)
                .toList();
    }

    /**
     * Converts nullable item priority into the normalized routing score.
     * Missing priority behaves as normal priority so old items continue to use
     * capacity-protecting routing.
     */
    private double priorityScore(Item item) {
        return item == null || item.getPriority() == null ? 0.0 : item.getPriority();
    }

    /**
     * Only items with a positive priority score wait for chute capacity.
     * Normal items keep recirculating so they do not block decision points while
     * capacity-constrained urgent items hold reservations.
     */
    private boolean canWaitForCapacity(double priorityScore) {
        return priorityScore > 0.0;
    }

    public record RoutingDecision(
            String selectedExitId,
            List<String> path,
            String nextConveyorId,
            boolean exitsSystem,
            RoutingStatus routingStatus,
            boolean capacityRelatedFailure) {
        public static RoutingDecision none() {
            return new RoutingDecision(null, null, null, false, RoutingStatus.UNROUTED, false);
        }
    }

    private record PathNode(String locationId, double distance) {
    }

    private record PathResult(List<String> locations, double travelSeconds) {
    }

    private record RouteCandidate(String exitId, PathResult path, double utilization) {
    }

    private record CapacityState(long occupancy, Integer capacity) {
        private double utilization() {
            return capacity == null ? 0.0 : (double) occupancy / capacity;
        }

        private boolean canAccept(double priorityScore) {
            if (capacity == null) {
                return true;
            }
            if (occupancy >= capacity) {
                return false;
            }
            return utilization() < capacityLimit(priorityScore);
        }

        private boolean isCapacityLimited(double priorityScore) {
            return capacity != null && occupancy >= capacity
                    || capacity != null && utilization() >= capacityLimit(priorityScore);
        }

        private double capacityLimit(double priorityScore) {
            return NORMAL_PRIORITY_CAPACITY_LIMIT
                    + (1.0 - NORMAL_PRIORITY_CAPACITY_LIMIT) * priorityScore;
        }
    }

    private record PendingRoutingDemand(
            String itemId,
            double priorityScore,
            Instant statusUpdatedAt,
            Instant entryTime,
            List<PendingCandidate> candidates) {
    }

    private record PendingCandidate(
            String exitId,
            int capacity,
            long baseOccupancy,
            double travelSeconds) {
    }
}
