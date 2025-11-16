package com.fiumen.backend.domain;

import fiumen.events.ItemSpeedChangedEvent;
import fiumen.events.LocationActivatedEvent;
import fiumen.events.LocationCreatedEvent;
import fiumen.events.LocationDeactivatedEvent;
import fiumen.events.LocationPropertiesUpdatedEvent;
import fiumen.events.LocationSpeedChangedEvent;
import fiumen.types.LocationType;
import lombok.Getter;
import lombok.Setter;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Getter
public class Location {

    private final String id;
    private String name;
    private LocationType type;
    private Boolean active;
    private Boolean isMainPath;

    private Map<String, Object> properties;

    private Double latitude;
    private Double longitude;
    private Double length;
    private Double speed;

    private Integer capacity = -1;

    @Setter
    private int itemCount = 0;

    private final Set<String> outboundConnectionIds = new HashSet<>();

    public Location(
            String id, String name, LocationType type, Boolean active, Boolean isMainPath,
            Map<String, Object> properties,
            Double latitude, Double longitude, Double length, Double speed,
            Integer capacity) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.active = active;
        this.isMainPath = isMainPath;
        this.properties = properties;
        this.latitude = latitude;
        this.longitude = longitude;
        this.length = length;
        this.speed = speed;
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
        this.length = event.getLength();
        this.speed = event.getSpeed();
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

    public void addConnectionTo(String toLocationId) {
        if (toLocationId != null && !this.id.equals(toLocationId)) {
            this.outboundConnectionIds.add(toLocationId);
        }
    }

    public void removeConnectionTo(String toLocationId) {
        this.outboundConnectionIds.remove(toLocationId);
    }

    public boolean isFull() {
        return this.capacity > 0 && this.itemCount >= this.capacity;
    }

    public boolean isTrack() {
        return this.length != null && this.length > 0;
    }

    public void setIsMainPath(Boolean isMainPath) {
        this.isMainPath = isMainPath;
    }

    public void updateSpeed(double newSpeed) {
        this.speed = newSpeed;
    }

    public void updateLength(double newLength) {
        this.length = newLength;
    }

    public void updateCoordinates(double latitude, double longitude) {
        this.latitude = latitude;
        this.longitude = longitude;
    }

    public void updateCapacity(Integer capacity) {
        this.capacity = capacity;
    }

    /**
     * Calculates the transit time in seconds to cross this location.
     * 
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