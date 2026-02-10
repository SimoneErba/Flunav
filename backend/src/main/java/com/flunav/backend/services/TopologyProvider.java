package com.flunav.backend.services;

import com.flunav.backend.domain.Conveyor;
import com.flunav.backend.domain.Location;
import flunav.types.PositionType;

import java.util.List;

public interface TopologyProvider {
    List<Location> getAllLocations();
    Location getLocationById(String id);
    List<Conveyor> getAllConveyors();
    Conveyor getConveyorById(String id);
    List<Conveyor> getOutgoingConveyors(String locationId);
    PositionType getPositionType(String id);
}
