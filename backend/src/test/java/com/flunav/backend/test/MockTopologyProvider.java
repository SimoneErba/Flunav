package com.flunav.backend.test;

import com.flunav.backend.domain.Conveyor;
import com.flunav.backend.domain.Location;
import com.flunav.backend.services.TopologyProvider;
import flunav.types.PositionType;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A TopologyProvider implementation that can be manually primed with stubs for testing.
 */
@Service
@Primary
public class MockTopologyProvider implements TopologyProvider {

    private final Map<String, Location> locationStubs = new HashMap<>();
    private final Map<String, Conveyor> conveyorStubs = new HashMap<>();

    public void stubLocation(Location loc) {
        locationStubs.put(loc.getId(), loc);
    }

    public void stubConveyor(Conveyor conv) {
        conveyorStubs.put(conv.getId(), conv);
    }

    public void clear() {
        locationStubs.clear();
        conveyorStubs.clear();
    }

    @Override
    public List<Location> getAllLocations() {
        return new ArrayList<>(locationStubs.values());
    }

    @Override
    public Location getLocationById(String id) {
        return locationStubs.get(id);
    }

    @Override
    public List<Conveyor> getAllConveyors() {
        return new ArrayList<>(conveyorStubs.values());
    }

    @Override
    public Conveyor getConveyorById(String id) {
        return conveyorStubs.get(id);
    }

    @Override
    public List<Conveyor> getOutgoingConveyors(String locationId) {
        return conveyorStubs.values().stream()
                .filter(c -> c.getSourceLocationId().equals(locationId))
                .toList();
    }

    @Override
    public PositionType getPositionType(String id) {
        if (conveyorStubs.containsKey(id)) {
            return PositionType.CONVEYOR;
        }
        if (locationStubs.containsKey(id)) {
            return PositionType.LOCATION;
        }
        return null;
    }
}
