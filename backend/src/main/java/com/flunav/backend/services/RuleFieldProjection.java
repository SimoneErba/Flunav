package com.flunav.backend.services;

import com.flunav.backend.domain.Conveyor;
import com.flunav.backend.models.input.ItemInput;
import com.flunav.backend.models.response.ConveyorResponse;
import com.flunav.backend.models.response.ItemResponse;
import com.flunav.backend.models.response.LocationResponse;
import flunav.events.ConnectionCreatedEvent;
import java.util.HashMap;
import java.util.Map;

/** Fields exposed to display and destination rules by event and snapshot projections. */
public final class RuleFieldProjection {
    private RuleFieldProjection() {}

    public static Map<String, Object> itemRootFields(ItemInput item) {
        Map<String, Object> fields = new HashMap<>();
        fields.put("id", item.getId());
        fields.put("name", item.getName());
        fields.put("speed", item.getSpeed());
        fields.put("priority", item.getPriority());
        fields.put("active", item.getActive());
        fields.put("locationId", item.getLocationId());
        fields.put("positionType", item.getPositionType());
        fields.put("progress", item.getProgress());
        fields.put("destinations", item.getDestinations());
        fields.put("timestamp", item.getTimestamp());
        return fields;
    }

    public static Map<String, Object> itemRootFields(com.flunav.backend.domain.Item item) {
        Map<String, Object> fields = new HashMap<>();
        fields.put("id", item.getId());
        fields.put("name", item.getName());
        fields.put("active", item.isActive());
        fields.put("priority", item.getPriority());
        fields.put("positionId", item.getPositionId());
        fields.put("positionType", item.getPositionType());
        fields.put("entryTimestamp", item.getEntryTimestamp());
        fields.put("routingStatus", item.getRoutingStatus());
        return fields;
    }

    public static Map<String, Object> locationRootFields(com.flunav.backend.domain.Location location) {
        Map<String, Object> fields = new HashMap<>();
        fields.put("id", location.getId());
        fields.put("name", location.getName());
        fields.put("type", location.getType());
        fields.put("active", location.getActive());
        fields.put("latitude", location.getLatitude());
        fields.put("longitude", location.getLongitude());
        fields.put("capacity", location.getCapacity());
        fields.put("timeToProcessMs", location.getTimeToProcessMs());
        return fields;
    }

    public static Map<String, Object> conveyorRootFields(Conveyor conveyor) {
        Map<String, Object> fields = new HashMap<>();
        fields.put("id", conveyor.getId());
        fields.put("sourceId", conveyor.getSourceLocationId());
        fields.put("targetId", conveyor.getTargetLocationId());
        fields.put("length", conveyor.getLength());
        fields.put("speed", conveyor.getSpeed());
        fields.put("minDistance", conveyor.getMinDistance());
        fields.put("type", conveyor.getType());
        fields.put("active", conveyor.isActive());
        fields.put("mainPath", conveyor.isMainPath());
        fields.put("capacity", conveyor.getCapacity());
        return fields;
    }

    public static Map<String, Object> connectionRootFields(ConnectionCreatedEvent event) {
        Map<String, Object> fields = new HashMap<>();
        fields.put("id", event.getConnectionId());
        fields.put("sourceId", event.getSourceId());
        fields.put("targetId", event.getTargetId());
        fields.put("name", event.getName());
        fields.put("length", event.getLength());
        fields.put("speed", event.getSpeed());
        fields.put("minDistance", event.getMinDistance());
        fields.put("type", event.getType());
        fields.put("active", event.getIsActive());
        fields.put("mainPath", event.getMainPath());
        fields.put("capacity", event.getCapacity());
        return fields;
    }

    public static Map<String, Object> itemRootFields(ItemResponse item) {
        Map<String, Object> fields = new HashMap<>();
        fields.put("id", item.getId());
        fields.put("name", item.getName());
        fields.put("active", item.getActive());
        fields.put("priority", item.getPriority());
        fields.put("effectivePriority", item.getEffectivePriority());
        fields.put("rushActive", item.getRushActive());
        fields.put("locationId", item.getLocationId());
        fields.put("currentEdgeId", item.getCurrentEdgeId());
        fields.put("entryTimestamp", item.getEntryTimestamp());
        fields.put("progress", item.getProgress());
        fields.put("destinations", item.getDestinations());
        fields.put("selectedExitId", item.getSelectedExitId());
        fields.put("routingStatus", item.getRoutingStatus());
        fields.put("path", item.getPath());
        return fields;
    }

    public static Map<String, Object> locationRootFields(LocationResponse location) {
        Map<String, Object> fields = new HashMap<>();
        fields.put("id", location.getId());
        fields.put("name", location.getName());
        fields.put("type", location.getType());
        fields.put("active", location.getActive());
        fields.put("capacity", location.getCapacity());
        fields.put("latitude", location.getLatitude());
        fields.put("longitude", location.getLongitude());
        fields.put("timeToProcessMs", location.getTimeToProcessMs());
        return fields;
    }

    public static Map<String, Object> conveyorRootFields(ConveyorResponse conveyor) {
        Map<String, Object> fields = new HashMap<>();
        fields.put("id", conveyor.getId());
        fields.put("name", conveyor.getName());
        fields.put("sourceId", conveyor.getSourceId());
        fields.put("targetId", conveyor.getTargetId());
        fields.put("length", conveyor.getLength());
        fields.put("speed", conveyor.getSpeed());
        fields.put("active", conveyor.getActive());
        fields.put("mainPath", conveyor.getMainPath());
        fields.put("capacity", conveyor.getCapacity());
        return fields;
    }
}
