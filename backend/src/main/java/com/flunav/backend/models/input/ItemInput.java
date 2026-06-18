package com.flunav.backend.models.input;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import flunav.types.RoutingStatus;

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
    private List<String> destinations;
    @JsonIgnore
    private String selectedExitId;
    @JsonIgnore
    private RoutingStatus routingStatus;
    @JsonIgnore
    private Instant routingStatusUpdatedAt;
    @JsonIgnore
    private List<String> path;
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
