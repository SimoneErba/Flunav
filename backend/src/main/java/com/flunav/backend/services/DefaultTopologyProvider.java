package com.flunav.backend.services;

import com.flunav.backend.domain.Conveyor;
import com.flunav.backend.domain.Location;
import flunav.types.PositionType;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class DefaultTopologyProvider implements TopologyProvider {

    private final LocationService locationService;
    private final ConveyorService conveyorService;

    public DefaultTopologyProvider(LocationService locationService, ConveyorService conveyorService) {
        this.locationService = locationService;
        this.conveyorService = conveyorService;
    }

    @Override
    public List<Location> getAllLocations() {
        return locationService.getAllLocations();
    }

    @Override
    public Location getLocationById(String id) {
        return locationService.getLocationById(id);
    }

    @Override
    public List<Conveyor> getAllConveyors() {
        return conveyorService.getAllConveyors();
    }

    @Override
    public Conveyor getConveyorById(String id) {
        return conveyorService.getConveyorById(id);
    }

    @Override
    public List<Conveyor> getOutgoingConveyors(String locationId) {
        return conveyorService.getOutgoingConveyors(locationId);
    }

    @Override
    public PositionType getPositionType(String id) {
        return locationService.getPositionType(id);
    }
}
