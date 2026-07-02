package com.flunav.backend.domain;

import flunav.events.LocationActivatedEvent;
import flunav.events.LocationCreatedEvent;
import flunav.events.LocationDeactivatedEvent;
import flunav.events.LocationPropertiesUpdatedEvent;
import flunav.types.LocationType;
import lombok.Getter;
import lombok.Setter;

import java.util.HashMap;
import java.util.Map;

@Getter
public class Location {

    private final String id;
    private String name;
    private LocationType type;
    private Boolean active;
    private Map<String, Object> properties;

    // Coordinates (The "Dot" on the map)
    private Double latitude;
    private Double longitude;

    // Capacity is optional (mostly for Chutes/Sinks/Buffers)
    // Default to -1 (Unlimited)
    private Integer capacity = -1;

    // Optional processing delay for TIMED_NODE locations.
    private Long timeToProcessMs;

    // Calculated field (populated by Service from Redis/Graph state)
    // This is NOT stored in OrientDB as a property, but calculated at runtime.
    @Setter
    private int currentItemCount = 0;

    // REMOVED: outboundConnectionIds
    // Connections are now 'Conveyor' objects (Edges).
    // The topology is managed by the GraphService/ConveyorService.

    public Location(
            String id, String name, LocationType type, Boolean active,
            Map<String, Object> properties,
            Double latitude, Double longitude,
            Integer capacity,
            Long timeToProcessMs) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.active = active;
        this.properties = properties;
        this.latitude = latitude;
        this.longitude = longitude;
        this.capacity = capacity;
        this.timeToProcessMs = timeToProcessMs;
    }

    public Location(LocationCreatedEvent event) {
        this.id = event.getEntityId();
        this.name = event.getName();
        this.type = event.getType();
        this.active = event.getActive();
        this.properties = event.getProperties();
        this.latitude = event.getLatitude();
        this.longitude = event.getLongitude();
        this.capacity = event.getCapacity();
        this.timeToProcessMs = event.getTimeToProcessMs();
    }

    public void updateProperties(LocationPropertiesUpdatedEvent event) {
        if (event.getUpdatedProperties() != null) {
            if (this.properties == null) {
                this.properties = new HashMap<>();
            }
            this.properties.putAll(event.getUpdatedProperties());
        }
    }

    public void activate(LocationActivatedEvent event) {
        this.active = true;
    }

    public void deactivate(LocationDeactivatedEvent event) {
        this.active = false;
    }

    public boolean isFull() {
        return this.capacity > 0 && this.currentItemCount >= this.capacity;
    }

    public void updateCoordinates(double latitude, double longitude) {
        this.latitude = latitude;
        this.longitude = longitude;
    }

    public void updateCapacity(Integer capacity) {
        this.capacity = capacity;
    }

    public void updateTimeToProcessMs(Long timeToProcessMs) {
        this.timeToProcessMs = timeToProcessMs;
    }

    public void setType(LocationType type) {
        this.type = type;
    }
}
