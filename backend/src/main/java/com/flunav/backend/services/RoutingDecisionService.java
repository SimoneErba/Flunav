package com.flunav.backend.services;

import com.flunav.backend.domain.Conveyor;
import com.flunav.backend.domain.Item;
import com.flunav.backend.domain.Location;
import com.flunav.backend.repositories.LiveLocationRepository;
import flunav.types.LocationType;
import flunav.types.PositionType;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

@Service
public class RoutingDecisionService {
    private static final double NORMAL_PRIORITY_CAPACITY_LIMIT = 0.90;
    private static final double HIGH_NUMERIC_PRIORITY = 10.0;

    private final TopologyProvider topologyProvider;
    private final DestinationExitMappingService destinationExitMappingService;
    private final LiveLocationRepository liveLocationRepository;

    public RoutingDecisionService(
            TopologyProvider topologyProvider,
            DestinationExitMappingService destinationExitMappingService,
            LiveLocationRepository liveLocationRepository) {
        this.topologyProvider = topologyProvider;
        this.destinationExitMappingService = destinationExitMappingService;
        this.liveLocationRepository = liveLocationRepository;
    }

    public RoutingDecision selectRoute(Item item, String sourceId, PositionType sourceType) {
        if (item == null || sourceId == null) {
            return RoutingDecision.none();
        }

        String sourceLocationId = resolveSourceLocation(sourceId, sourceType);
        if (sourceLocationId == null) {
            return RoutingDecision.none();
        }

        boolean highPriority = isHighPriority(item);
        List<RouteCandidate> candidates = new ArrayList<>();
        for (String exitId : resolveCandidateExits(item.getDestinations())) {
            Location exit = findLocation(exitId);
            if (exit == null) {
                continue;
            }

            CapacityState capacity = capacityState(exit);
            if (!capacity.canAccept(highPriority)) {
                continue;
            }

            PathResult path = calculateAvailablePath(sourceLocationId, exitId);
            if (path != null) {
                candidates.add(new RouteCandidate(exitId, path, capacity.utilization()));
            }
        }

        Comparator<RouteCandidate> comparator = highPriority
                ? Comparator.comparingDouble(candidate -> candidate.path().travelSeconds())
                : Comparator.comparingDouble(RouteCandidate::utilization)
                        .thenComparingDouble(candidate -> candidate.path().travelSeconds());

        return candidates.stream()
                .min(comparator)
                .map(candidate -> new RoutingDecision(
                        candidate.exitId(),
                        candidate.path().locations(),
                        firstConveyor(candidate.path().locations()),
                        true))
                .orElseGet(() -> fallbackDecision(sourceLocationId));
    }

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
        if (sourceLocationId == null || exit == null
                || !capacityState(exit).canAccept(isHighPriority(item))) {
            return sourceLocationId == null ? RoutingDecision.none() : fallbackDecision(sourceLocationId);
        }

        PathResult path = calculateAvailablePath(sourceLocationId, exitId);
        if (path == null) {
            return fallbackDecision(sourceLocationId);
        }
        return new RoutingDecision(exitId, path.locations(), firstConveyor(path.locations()), true);
    }

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

    private PathResult calculateAvailablePath(String sourceLocationId, String exitId) {
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
                return new PathResult(buildPath(previous, sourceLocationId, exitId), current.distance());
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
                false);
    }

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

    private String resolveSourceLocation(String sourceId, PositionType sourceType) {
        if (sourceType == PositionType.CONVEYOR) {
            Conveyor conveyor = findConveyor(sourceId);
            return conveyor != null ? conveyor.getTargetLocationId() : null;
        }
        return findLocation(sourceId) != null ? sourceId : null;
    }

    private boolean isAvailable(Conveyor conveyor) {
        return conveyor != null
                && conveyor.isActive()
                && conveyor.getSpeed() != null
                && conveyor.getSpeed() > 0;
    }

    private Location findLocation(String locationId) {
        try {
            return topologyProvider.getLocationById(locationId);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private Conveyor findConveyor(String conveyorId) {
        try {
            return topologyProvider.getConveyorById(conveyorId);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private double travelSeconds(Conveyor conveyor) {
        double length = conveyor.getLength() != null ? conveyor.getLength() : 0.0;
        return Math.max(length / conveyor.getSpeed(), 0.001);
    }

    private CapacityState capacityState(Location exit) {
        if (exit.getType() != LocationType.CHUTE) {
            return new CapacityState(0, null);
        }
        Integer capacity = exit.getCapacity();
        long occupancy = liveLocationRepository.getItemCount(exit.getId());
        if (capacity == null || capacity <= 0) {
            return new CapacityState(occupancy, null);
        }
        return new CapacityState(occupancy, capacity);
    }

    private boolean isHighPriority(Item item) {
        if (item.getProperties() == null) {
            return false;
        }
        Object value = item.getProperties().entrySet().stream()
                .filter(entry -> "priority".equalsIgnoreCase(entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
        if (value == null) {
            return false;
        }
        if (value instanceof Number number) {
            return number.doubleValue() >= HIGH_NUMERIC_PRIORITY;
        }
        String priority = value.toString().trim().toUpperCase(Locale.ROOT);
        return Set.of("HIGH", "EXPEDITE", "URGENT", "CRITICAL").contains(priority);
    }

    public record RoutingDecision(
            String selectedExitId,
            List<String> path,
            String nextConveyorId,
            boolean exitsSystem) {
        public static RoutingDecision none() {
            return new RoutingDecision(null, null, null, false);
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

        private boolean canAccept(boolean highPriority) {
            if (capacity == null) {
                return true;
            }
            if (occupancy >= capacity) {
                return false;
            }
            return highPriority || utilization() < NORMAL_PRIORITY_CAPACITY_LIMIT;
        }
    }
}
