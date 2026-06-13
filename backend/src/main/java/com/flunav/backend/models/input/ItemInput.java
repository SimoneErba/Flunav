package com.flunav.backend.models.input;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.Map;

@Getter
@Setter
public class ItemInput {
    private String id;
    private String name;
    private Double speed;
    private Boolean active;
    private String locationId;
    private flunav.types.PositionType positionType;
    private Double progress;
    private java.util.List<String> destinations;
    private String selectedExitId;
    private java.util.List<String> path;
    private Map<String, Object> properties;
    private Instant timestamp;

    public ItemInput() {
    }

    public ItemInput(flunav.events.ItemCreatedEvent event) {
        this.id = event.getEntityId();
        this.name = event.getName();
        this.speed = event.getSpeed();
        this.active = event.isActive();
        this.locationId = event.getLocationId();
        this.positionType = event.getPositionType();
        this.progress = event.getProgress();
        this.destinations = event.getDestinations();
        this.properties = event.getProperties();
        this.timestamp = event.getTimestamp();
    }
}
