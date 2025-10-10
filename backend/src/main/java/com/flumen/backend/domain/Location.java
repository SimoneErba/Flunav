package com.flumen.backend.domain;

import flumen.events.LocationActivatedEvent;
import flumen.events.LocationCreatedEvent;
import flumen.events.LocationDeactivatedEvent;
import flumen.events.LocationPropertiesUpdatedEvent;
import flumen.types.LocationType;
import lombok.Getter;
import lombok.Setter;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Getter
public class Location {

    // --- Core Identity & Metadata ---
    private final String id; // ID is immutable after creation
    private String name;
    private LocationType type; // e.g., "CONVEYOR", "JUNCTION", "CHUTE", "ACCUMULATION"
    private Boolean active;
    private Map<String, Object> properties;

    // --- Physical Properties (for tracks) ---
    private Double latitude;
    private Double longitude;
    private Double length; // Can be null if it's not a physical track (e.g., a Junction)
    private Double speed;  // Can be null if it's not motorized

    // --- Behavioral Properties ---
    private boolean isDischargePoint = false; // Does this location remove items from the active graph?
    private int capacity = -1;                // Max item capacity (-1 for infinite)
    
    @Setter // This property is dynamic and changes frequently
    private int itemCount = 0;                  // Current number of items in this location

    // --- Graph Relationships ---
    private final Set<String> outboundConnectionIds = new HashSet<>();

    /**
     * Full constructor for creating a Location with all its properties.
     */
    public Location(
        String id, String name, LocationType type, Boolean active, Map<String, Object> properties,
        Double latitude, Double longitude, Double length, Double speed,
        boolean isDischargePoint, int capacity
    ) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.active = active;
        this.properties = properties;
        this.latitude = latitude;
        this.longitude = longitude;
        this.length = length;
        this.speed = speed;
        this.isDischargePoint = isDischargePoint;
        this.capacity = capacity;
    }

    /**
     * Constructor to build the object from a creation event (Event Sourcing pattern).
     */
    public Location(LocationCreatedEvent event) {
        this.id = event.getEntityId();
        this.name = event.getName();
        this.type = event.getType();
        this.active = event.getActive();
        this.properties = event.getProperties();
        this.latitude = event.getLatitude();
        this.longitude = event.getLongitude();
        this.length = event.getLength();
        this.speed = event.getSpeed();
        this.isDischargePoint = event.isDischargePoint();
        this.capacity = event.getCapacity();
    }

    // --- State Mutation Methods (driven by events) ---

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

    public void addConnectionTo(String toLocationId) {
        if (toLocationId != null && !this.id.equals(toLocationId)) {
            this.outboundConnectionIds.add(toLocationId);
        }
    }

    public void removeConnectionTo(String toLocationId) {
        this.outboundConnectionIds.remove(toLocationId);
    }

    // --- "Smart" Helper Methods (encapsulated business logic) ---

    /**
     * Checks if this location can accept more items based on its capacity.
     */
    public boolean isFull() {
        return this.capacity > 0 && this.itemCount >= this.capacity;
    }
    
    /**
     * Checks if this location represents a physical path with a calculable transit time.
     */
    public boolean isTrack() {
        return this.length != null && this.length > 0;
    }

    /**
     * Calculates the transit time in seconds to cross this location.
     * @return Transit time, or infinity if not a valid track.
     */
    public double getTransitTimeSeconds() {
        if (!isTrack() || this.speed == null || this.speed <= 0) {
            return Double.POSITIVE_INFINITY;
        }
        return this.length / this.speed;
    }
    
    /**
     * Checks if an item can move from this location to a target location.
     */
    public boolean canMoveTo(String targetLocationId) {
        return this.outboundConnectionIds.contains(targetLocationId);
    }
}