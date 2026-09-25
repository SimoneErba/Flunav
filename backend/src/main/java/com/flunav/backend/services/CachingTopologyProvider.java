package com.flunav.backend.services;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import com.flunav.backend.context.SimulationBuildCacheContext;
import com.flunav.backend.domain.Conveyor;
import com.flunav.backend.domain.Location;

import flunav.types.PositionType;

/** Reads an isolated topology once per build worker and discards it when that scope closes. */
@Service
@Primary
public class CachingTopologyProvider implements TopologyProvider {
    private final DefaultTopologyProvider source;

    public CachingTopologyProvider(DefaultTopologyProvider source) {
        this.source = source;
    }

    public void invalidate() {
        SimulationBuildCacheContext.invalidateTopology();
    }

    private Snapshot current() {
        if (!SimulationBuildCacheContext.enabled() || SimulationBuildCacheContext.topologyBypassed()) {
            return null;
        }
        Snapshot cached = (Snapshot) SimulationBuildCacheContext.topologySnapshot();
        if (cached == null) {
            List<Location> locations = List.copyOf(source.getAllLocations());
            List<Conveyor> conveyors = List.copyOf(source.getAllConveyors());
            cached = new Snapshot(locations, conveyors,
                    locations.stream().collect(Collectors.toMap(location -> key(location.getId()), location -> location)),
                    conveyors.stream().collect(Collectors.toMap(conveyor -> key(conveyor.getId()), conveyor -> conveyor)),
                    locations.stream().collect(Collectors.toMap(location -> key(location.getId()),
                            location -> List.copyOf(source.getOutgoingConveyors(location.getId())))));
            SimulationBuildCacheContext.setTopologySnapshot(cached);
        }
        return cached;
    }

    private static String key(String id) {
        return id.toLowerCase(Locale.ROOT);
    }

    @Override public List<Location> getAllLocations() {
        Snapshot cached = current();
        return cached == null ? source.getAllLocations() : cached.locations();
    }

    @Override public Location getLocationById(String id) {
        Snapshot cached = current();
        if (cached == null) return source.getLocationById(id);
        Location location = cached.locationsById().get(key(id));
        return location != null ? location : source.getLocationById(id);
    }

    @Override public List<Conveyor> getAllConveyors() {
        Snapshot cached = current();
        return cached == null ? source.getAllConveyors() : cached.conveyors();
    }

    @Override public Conveyor getConveyorById(String id) {
        Snapshot cached = current();
        if (cached == null) return source.getConveyorById(id);
        Conveyor conveyor = cached.conveyorsById().get(key(id));
        return conveyor != null ? conveyor : source.getConveyorById(id);
    }

    @Override public List<Conveyor> getOutgoingConveyors(String locationId) {
        Snapshot cached = current();
        return cached == null ? source.getOutgoingConveyors(locationId)
                : cached.outgoing().getOrDefault(key(locationId), List.of());
    }

    @Override public PositionType getPositionType(String id) {
        Snapshot cached = current();
        if (cached == null) return source.getPositionType(id);
        if (cached.locationsById().containsKey(key(id))) return PositionType.LOCATION;
        if (cached.conveyorsById().containsKey(key(id))) return PositionType.CONVEYOR;
        return source.getPositionType(id);
    }

    private record Snapshot(List<Location> locations, List<Conveyor> conveyors,
            Map<String, Location> locationsById, Map<String, Conveyor> conveyorsById,
            Map<String, List<Conveyor>> outgoing) {
    }

}
