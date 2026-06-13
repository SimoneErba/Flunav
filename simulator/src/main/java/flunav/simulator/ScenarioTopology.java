package flunav.simulator;

import flunav.events.ConnectionCreatedEvent;
import flunav.events.ConnectionDeletedEvent;
import flunav.events.LocationCreatedEvent;
import flunav.events.LocationDeletedEvent;
import flunav.types.ConveyorType;
import flunav.types.LocationType;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class ScenarioTopology {
    record LocationSpec(
            String id,
            String name,
            double latitude,
            double longitude,
            LocationType type,
            int capacity,
            Map<String, Object> properties) {
        LocationSpec {
            properties = Map.copyOf(properties);
        }
    }

    record ConveyorSpec(
            String id,
            String sourceId,
            String targetId,
            double length,
            double speed,
            boolean mainPath,
            Map<String, Object> properties) {
        ConveyorSpec {
            properties = Map.copyOf(properties);
        }
    }

    record FailureSpec(
            String conveyorId,
            List<String> bypassPath,
            List<String> inFlightPath,
            String errorCode) {
        FailureSpec {
            bypassPath = List.copyOf(bypassPath);
            inFlightPath = List.copyOf(inFlightPath);
        }
    }

    private final String name;
    private final String itemPrefix;
    private final Map<String, LocationSpec> locations = new LinkedHashMap<>();
    private final List<ConveyorSpec> conveyors = new ArrayList<>();
    private final List<String> entries = new ArrayList<>();
    private final List<String> exits = new ArrayList<>();
    private final List<List<String>> alternateRoutes = new ArrayList<>();
    private final List<List<String>> reacquisitionRoutes = new ArrayList<>();
    private final List<FailureSpec> failures = new ArrayList<>();

    ScenarioTopology(String name, String itemPrefix) {
        this.name = name;
        this.itemPrefix = itemPrefix;
    }

    ScenarioTopology location(String id, double latitude, double longitude, LocationType type, String zone) {
        int capacity = type == LocationType.CHUTE ? 250 : 0;
        LocationSpec previous = locations.put(id,
                new LocationSpec(id, id, latitude, longitude, type, capacity, Map.of("zone", zone)));
        if (previous != null) {
            throw new IllegalArgumentException("Duplicate location: " + id);
        }
        return this;
    }

    ScenarioTopology conveyor(
            String sourceId,
            String targetId,
            double length,
            double speed,
            boolean mainPath,
            String routeClass) {
        conveyors.add(new ConveyorSpec(
                conveyorId(sourceId, targetId),
                sourceId,
                targetId,
                length,
                speed,
                mainPath,
                Map.of("routeClass", routeClass)));
        return this;
    }

    ScenarioTopology entries(String... locationIds) {
        entries.addAll(List.of(locationIds));
        return this;
    }

    ScenarioTopology exits(String... locationIds) {
        exits.addAll(List.of(locationIds));
        return this;
    }

    ScenarioTopology alternateRoute(String... locationIds) {
        alternateRoutes.add(List.of(locationIds));
        return this;
    }

    ScenarioTopology reacquisitionRoute(String... locationIds) {
        reacquisitionRoutes.add(List.of(locationIds));
        return this;
    }

    ScenarioTopology failure(
            String sourceId,
            String targetId,
            List<String> bypassPath,
            List<String> inFlightPath,
            String errorCode) {
        failures.add(new FailureSpec(conveyorId(sourceId, targetId), bypassPath, inFlightPath, errorCode));
        return this;
    }

    void validate() {
        if (locations.isEmpty() || conveyors.isEmpty() || entries.isEmpty() || exits.isEmpty()) {
            throw new IllegalStateException(name + " topology is incomplete");
        }

        Map<String, List<ConveyorSpec>> outgoing = outgoingConveyors(null);
        Set<String> conveyorIds = new HashSet<>();
        Set<String> directedPairs = new HashSet<>();
        for (ConveyorSpec conveyor : conveyors) {
            requireLocation(conveyor.sourceId());
            requireLocation(conveyor.targetId());
            if (conveyor.length() <= 0 || conveyor.speed() <= 0) {
                throw new IllegalStateException("Conveyor must have positive length and speed: " + conveyor.id());
            }
            if (!conveyorIds.add(conveyor.id())) {
                throw new IllegalStateException("Duplicate conveyor ID: " + conveyor.id());
            }
            if (!directedPairs.add(conveyor.sourceId() + "\u0000" + conveyor.targetId())) {
                throw new IllegalStateException(
                        "Duplicate directed conveyor: " + conveyor.sourceId() + " -> " + conveyor.targetId());
            }
        }

        for (Map.Entry<String, List<ConveyorSpec>> entry : outgoing.entrySet()) {
            if (entry.getValue().size() <= 1) {
                continue;
            }
            long mainPaths = entry.getValue().stream().filter(ConveyorSpec::mainPath).count();
            if (mainPaths != 1) {
                throw new IllegalStateException(
                        "Decision point must have exactly one main-path fallback: " + entry.getKey());
            }
        }

        entries.forEach(this::requireLocation);
        exits.forEach(this::requireLocation);
        alternateRoutes.forEach(route -> validatePath(route, null));
        reacquisitionRoutes.forEach(route -> validatePath(route, null));

        for (String entry : entries) {
            for (String exit : exits) {
                if (!isReachable(entry, exit, null)) {
                    throw new IllegalStateException("Exit " + exit + " is unreachable from entry " + entry);
                }
            }
        }
        if (!hasDirectedCycle(outgoing)) {
            throw new IllegalStateException(name + " topology must contain a directed recirculation cycle");
        }

        for (FailureSpec failure : failures) {
            ConveyorSpec critical = conveyors.stream()
                    .filter(conveyor -> conveyor.id().equals(failure.conveyorId()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "Failure references missing conveyor: " + failure.conveyorId()));
            validatePath(failure.bypassPath(), failure.conveyorId());
            validatePath(failure.inFlightPath(), null);
            if (!failure.bypassPath().get(0).equals(critical.sourceId())
                    || !failure.bypassPath().get(failure.bypassPath().size() - 1).equals(critical.targetId())) {
                throw new IllegalStateException(
                        "Failure bypass must connect the critical conveyor endpoints: " + failure.conveyorId());
            }
            if (!failure.inFlightPath().get(0).equals(critical.sourceId())
                    || failure.inFlightPath().size() < 2
                    || !failure.inFlightPath().get(1).equals(critical.targetId())) {
                throw new IllegalStateException(
                        "In-flight failure path must begin with the critical conveyor: " + failure.conveyorId());
            }
            if (!isReachable(critical.sourceId(), critical.targetId(), failure.conveyorId())) {
                throw new IllegalStateException("Failure has no verified alternate route: " + failure.conveyorId());
            }
        }
    }

    void create() throws Exception {
        for (LocationSpec location : locations.values()) {
            SimulatorUtils.sendEvent(new LocationCreatedEvent(
                    location.id(),
                    location.name(),
                    true,
                    location.latitude(),
                    location.longitude(),
                    location.type(),
                    location.capacity(),
                    location.properties()), "POST");
        }

        long setupDelayMs = Long.parseLong(System.getenv().getOrDefault("SIMULATOR_SETUP_DELAY_MS", "1500"));
        Thread.sleep(setupDelayMs);

        for (ConveyorSpec conveyor : conveyors) {
            long timeToTraverseMs = Math.round((conveyor.length() / conveyor.speed()) * 1000);
            SimulatorUtils.sendEvent(new ConnectionCreatedEvent(
                    conveyor.id(),
                    conveyor.sourceId(),
                    conveyor.targetId(),
                    conveyor.length(),
                    conveyor.speed(),
                    0.0,
                    timeToTraverseMs,
                    conveyor.mainPath(),
                    conveyor.id(),
                    true,
                    ConveyorType.BELT,
                    0,
                    conveyor.properties()), "POST");
        }
    }

    void destroy() throws Exception {
        for (int index = conveyors.size() - 1; index >= 0; index--) {
            ConveyorSpec conveyor = conveyors.get(index);
            SimulatorUtils.sendEvent(
                    new ConnectionDeletedEvent(conveyor.sourceId(), conveyor.targetId()), "DELETE");
        }
        List<LocationSpec> locationList = new ArrayList<>(locations.values());
        for (int index = locationList.size() - 1; index >= 0; index--) {
            SimulatorUtils.sendEvent(new LocationDeletedEvent(locationList.get(index).id()), "DELETE");
        }
    }

    String name() {
        return name;
    }

    String itemPrefix() {
        return itemPrefix;
    }

    List<String> entries() {
        return List.copyOf(entries);
    }

    List<String> exits() {
        return List.copyOf(exits);
    }

    List<List<String>> alternateRoutes() {
        return List.copyOf(alternateRoutes);
    }

    List<List<String>> reacquisitionRoutes() {
        return List.copyOf(reacquisitionRoutes);
    }

    List<FailureSpec> failures() {
        return List.copyOf(failures);
    }

    int locationCount() {
        return locations.size();
    }

    int conveyorCount() {
        return conveyors.size();
    }

    private void validatePath(List<String> path, String excludedConveyorId) {
        if (path.size() < 2) {
            throw new IllegalStateException("Route must contain at least two locations: " + path);
        }
        Map<String, List<ConveyorSpec>> outgoing = outgoingConveyors(excludedConveyorId);
        for (int index = 0; index < path.size() - 1; index++) {
            String sourceId = path.get(index);
            String targetId = path.get(index + 1);
            requireLocation(sourceId);
            requireLocation(targetId);
            boolean connected = outgoing.getOrDefault(sourceId, List.of()).stream()
                    .anyMatch(conveyor -> conveyor.targetId().equals(targetId));
            if (!connected) {
                throw new IllegalStateException(
                        "Invalid directed route segment " + sourceId + " -> " + targetId + " in " + path);
            }
        }
    }

    private boolean isReachable(String sourceId, String targetId, String excludedConveyorId) {
        Map<String, List<ConveyorSpec>> outgoing = outgoingConveyors(excludedConveyorId);
        Deque<String> pending = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        pending.add(sourceId);
        while (!pending.isEmpty()) {
            String current = pending.removeFirst();
            if (!visited.add(current)) {
                continue;
            }
            if (current.equals(targetId)) {
                return true;
            }
            outgoing.getOrDefault(current, List.of()).stream()
                    .map(ConveyorSpec::targetId)
                    .filter(target -> !visited.contains(target))
                    .forEach(pending::addLast);
        }
        return false;
    }

    private boolean hasDirectedCycle(Map<String, List<ConveyorSpec>> outgoing) {
        Set<String> visited = new HashSet<>();
        Set<String> active = new HashSet<>();
        for (String locationId : locations.keySet()) {
            if (detectCycle(locationId, outgoing, visited, active)) {
                return true;
            }
        }
        return false;
    }

    private boolean detectCycle(
            String locationId,
            Map<String, List<ConveyorSpec>> outgoing,
            Set<String> visited,
            Set<String> active) {
        if (active.contains(locationId)) {
            return true;
        }
        if (!visited.add(locationId)) {
            return false;
        }
        active.add(locationId);
        for (ConveyorSpec conveyor : outgoing.getOrDefault(locationId, List.of())) {
            if (detectCycle(conveyor.targetId(), outgoing, visited, active)) {
                return true;
            }
        }
        active.remove(locationId);
        return false;
    }

    private Map<String, List<ConveyorSpec>> outgoingConveyors(String excludedConveyorId) {
        Map<String, List<ConveyorSpec>> outgoing = new HashMap<>();
        for (ConveyorSpec conveyor : conveyors) {
            if (conveyor.id().equals(excludedConveyorId)) {
                continue;
            }
            outgoing.computeIfAbsent(conveyor.sourceId(), ignored -> new ArrayList<>()).add(conveyor);
        }
        return outgoing;
    }

    private void requireLocation(String locationId) {
        if (!locations.containsKey(locationId)) {
            throw new IllegalStateException("Unknown location: " + locationId);
        }
    }

    static String conveyorId(String sourceId, String targetId) {
        return "Conveyor_" + sourceId + "_" + targetId;
    }
}
