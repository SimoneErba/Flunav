package com.flunav.backend.repositories.support;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.flunav.backend.domain.Conveyor;
import com.flunav.backend.domain.Item;
import com.flunav.backend.domain.Location;
import com.flunav.backend.models.graph.GraphData;
import com.flunav.backend.models.response.ConveyorResponse;
import com.flunav.backend.models.response.ItemResponse;
import com.flunav.backend.models.response.LocationResponse;

import flunav.types.PositionType;

/** Mutable graph owned by exactly one short-lived multi-simulation run. */
public final class MultiSimulationGraph {
    private final Map<String, Location> locations = new LinkedHashMap<>();
    private final Map<String, Conveyor> conveyors = new LinkedHashMap<>();
    private final Map<String, Item> items = new HashMap<>();
    private final Map<String, List<String>> outgoing = new HashMap<>();

    public void restore(GraphData snapshot) {
        locations.clear();
        conveyors.clear();
        items.clear();
        outgoing.clear();
        if (snapshot.getLocations() != null) {
            for (LocationResponse value : snapshot.getLocations()) {
                Location location = new Location(value.getId(), value.getName(), value.getType(), value.getActive(),
                        copyMap(value.getProperties()), value.getLatitude(), value.getLongitude(),
                        value.getCapacity(), value.getTimeToProcessMs());
                location.setActiveAlarms(value.getActiveAlarms());
                putLocation(location);
            }
        }
        if (snapshot.getConveyors() != null) {
            for (ConveyorResponse value : snapshot.getConveyors()) {
                Conveyor conveyor = new Conveyor(value.getId(), value.getSourceId(), value.getTargetId(),
                        value.getLength(), value.getSpeed(), value.getMinDistance(), value.getType(),
                        !Boolean.FALSE.equals(value.getActive()),
                        value.getOperatorEnabled() == null ? !Boolean.FALSE.equals(value.getActive())
                                : value.getOperatorEnabled(),
                        value.getActiveAlarms(), value.getCapacity(), Boolean.TRUE.equals(value.getMainPath()),
                        copyMap(value.getProperties()));
                putConveyor(conveyor);
            }
        }
        if (snapshot.getItems() != null) {
            for (ItemResponse value : snapshot.getItems()) {
                putItem(new Item(value.getId(), value.getName(), !Boolean.FALSE.equals(value.getActive()),
                        value.getPriority(), copyMap(value.getProperties())));
            }
        }
    }

    public List<Location> locations() {
        return locations.values().stream().map(MultiSimulationGraph::copyLocation).toList();
    }

    public Location location(String id) {
        return copyLocation(locations.get(key(id)));
    }

    public void putLocation(Location location) {
        locations.put(key(location.getId()), copyLocation(location));
    }

    public void deleteLocation(String id) {
        locations.remove(key(id));
        List<String> edges = conveyors.values().stream()
                .filter(conveyor -> id.equalsIgnoreCase(conveyor.getSourceLocationId())
                        || id.equalsIgnoreCase(conveyor.getTargetLocationId()))
                .map(Conveyor::getId).toList();
        edges.forEach(this::deleteConveyor);
    }

    public List<Conveyor> conveyors() {
        return conveyors.values().stream().map(MultiSimulationGraph::copyConveyor).toList();
    }

    public Conveyor conveyor(String id) {
        return copyConveyor(conveyors.get(key(id)));
    }

    public void putConveyor(Conveyor conveyor) {
        Conveyor old = conveyors.put(key(conveyor.getId()), copyConveyor(conveyor));
        if (old != null && old.getSourceLocationId().equalsIgnoreCase(conveyor.getSourceLocationId())) {
            return;
        }
        if (old != null) {
            removeOutgoing(old);
        }
        outgoing.computeIfAbsent(key(conveyor.getSourceLocationId()), ignored -> new ArrayList<>())
                .add(key(conveyor.getId()));
    }

    public void deleteConveyor(String id) {
        Conveyor removed = conveyors.remove(key(id));
        if (removed != null) {
            removeOutgoing(removed);
        }
    }

    public List<Conveyor> outgoing(String locationId) {
        return outgoing.getOrDefault(key(locationId), List.of()).stream()
                .map(conveyors::get).filter(value -> value != null)
                .map(MultiSimulationGraph::copyConveyor).toList();
    }

    public PositionType positionType(String id) {
        if (locations.containsKey(key(id))) return PositionType.LOCATION;
        if (conveyors.containsKey(key(id))) return PositionType.CONVEYOR;
        throw new IllegalArgumentException("Unknown graph position: " + id);
    }

    public List<Item> items() {
        return items.values().stream().map(MultiSimulationGraph::copyItem).toList();
    }

    public Item item(String id) {
        return copyItem(items.get(key(id)));
    }

    public void putItem(Item item) {
        items.put(key(item.getId()), copyItem(item));
    }

    public void deleteItem(String id) {
        items.remove(key(id));
    }

    private void removeOutgoing(Conveyor conveyor) {
        List<String> ids = outgoing.get(key(conveyor.getSourceLocationId()));
        if (ids != null) {
            ids.remove(key(conveyor.getId()));
            if (ids.isEmpty()) outgoing.remove(key(conveyor.getSourceLocationId()));
        }
    }

    private static String key(String id) {
        return id.toLowerCase(Locale.ROOT);
    }

    private static Location copyLocation(Location value) {
        if (value == null) return null;
        Location copy = new Location(value.getId(), value.getName(), value.getType(), value.getActive(),
                copyMap(value.getProperties()), value.getLatitude(), value.getLongitude(),
                value.getCapacity(), value.getTimeToProcessMs());
        copy.setActiveAlarms(value.getActiveAlarms());
        copy.setCurrentItemCount(value.getCurrentItemCount());
        return copy;
    }

    private static Conveyor copyConveyor(Conveyor value) {
        if (value == null) return null;
        return new Conveyor(value.getId(), value.getSourceLocationId(), value.getTargetLocationId(),
                value.getLength(), value.getSpeed(), value.getMinDistance(), value.getType(),
                value.isActive(), value.isOperatorEnabled(), value.getActiveAlarms(), value.getCapacity(),
                value.isMainPath(), copyMap(value.getProperties()));
    }

    private static Item copyItem(Item value) {
        if (value == null) return null;
        Item copy = new Item(value.getId(), value.getName(), value.isActive(), value.getPriority(),
                copyMap(value.getProperties()));
        copy.setPositionId(value.getPositionId());
        copy.setPositionType(value.getPositionType());
        copy.setEntryTimestamp(value.getEntryTimestamp());
        copy.setDestinations(value.getDestinations() == null ? null : List.copyOf(value.getDestinations()));
        copy.setSelectedExitId(value.getSelectedExitId());
        copy.setRoutingStatus(value.getRoutingStatus());
        copy.setRoutingStatusUpdatedAt(value.getRoutingStatusUpdatedAt());
        copy.setPath(value.getPath() == null ? null : List.copyOf(value.getPath()));
        copy.setCurrentProgress(value.getCurrentProgress());
        return copy;
    }

    private static Map<String, Object> copyMap(Map<String, Object> value) {
        if (value == null) return null;
        Map<String, Object> copy = new HashMap<>();
        value.forEach((key, nested) -> copy.put(key, copyValue(nested)));
        return copy;
    }

    private static Object copyValue(Object value) {
        if (value instanceof Map<?, ?> nested) {
            Map<Object, Object> copy = new HashMap<>();
            nested.forEach((key, child) -> copy.put(key, copyValue(child)));
            return copy;
        }
        if (value instanceof List<?> nested) {
            List<Object> copy = new ArrayList<>(nested.size());
            nested.forEach(child -> copy.add(copyValue(child)));
            return copy;
        }
        return value;
    }
}
