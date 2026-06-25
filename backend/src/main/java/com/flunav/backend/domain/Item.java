package com.flunav.backend.domain;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import flunav.events.ItemCreatedEvent;
import flunav.events.ItemPropertiesUpdatedEvent;
import flunav.types.PositionType;
import flunav.types.RoutingStatus;

@Getter
public class Item {
    private final String id;

    @Setter
    private String name;

    private boolean active;
    @Setter
    private Double priority;
    private Map<String, Object> properties;

    @Setter
    private String positionId;

    @Setter
    private PositionType positionType;

    @Setter
    private Instant entryTimestamp;

    @Setter
    private List<String> destinations;

    @Setter
    private String selectedExitId;

    @Setter
    private RoutingStatus routingStatus;

    @Setter
    private Instant routingStatusUpdatedAt;

    @Setter
    private List<String> path;

    @Setter
    private Double currentProgress;

    public Item(String id, String name, boolean active, Map<String, Object> properties) {
        this(id, name, active, 0.0, properties);
    }

    public Item(String id, String name, boolean active, Double priority, Map<String, Object> properties) {
        this.id = id;
        this.name = name;
        this.active = active;
        this.priority = priority;
        this.properties = properties;
    }

    public Item(ItemCreatedEvent event) {
        this.id = event.getEntityId();
        this.name = event.getName();
        this.active = event.isActive();
        this.priority = event.getPriority();
        this.properties = event.getProperties();
    }

    public void resume() {
        this.active = true;
    }

    public void stop() {
        this.active = false;
    }

    public void updatePosition(String positionId, PositionType type, Instant time, Double progress) {
        this.positionId = positionId;
        this.positionType = type;
        this.entryTimestamp = time;
        this.currentProgress = (progress != null) ? progress : 0.0;
    }

    public void updateProperties(ItemPropertiesUpdatedEvent event) {
        if (event.getProperties() != null) {
            if (this.properties == null) {
                this.properties = new HashMap<>();
            }
            this.properties.putAll(event.getProperties());
        }
    }
}
