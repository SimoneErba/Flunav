package com.flonav.backend.domain;

import flonav.events.LocationActivatedEvent;
import flonav.events.LocationCreatedEvent;
import flonav.events.LocationDeactivatedEvent;
import flonav.events.LocationPropertiesUpdatedEvent;
import flonav.types.LocationType;
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
            Integer capacity) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.active = active;
        this.properties = properties;
        this.latitude = latitude;
        this.longitude = longitude;
        this.capacity = capacity;
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
}