package com.fiumen.backend.domain;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import fiumen.events.ItemCreatedEvent;
import fiumen.events.ItemPropertiesUpdatedEvent;

@Getter
public class Item {
    private final String id;

    @Setter
    private String name;

    private boolean active;
    private Map<String, Object> properties;

    // --- POSITIONING (Refactored to Strings) ---
    // The ID of the Conveyor (Edge) the item is currently traveling on.
    @Setter
    private String currentEdgeId;

    // The ID of the Junction (Node) the item just passed.
    // This helps identifying direction if multiple edges exist.
    @Setter
    private String lastNodeId;

    // Replaces 'lastKnownLocationTimestamp'.
    // Represents exactly when the item entered 'currentEdgeId'.
    @Setter
    private Instant entryTimestamp;

    // --- NAVIGATION ---
    @Setter
    private String destinationId; // The Target Node ID (Sink/Chute)

    @Setter
    private List<String> path; // List of Edge IDs to follow

    // --- TRANSIENT (Calculated) ---
    // Not stored in DB, but useful if you use this object for API responses
    @Setter
    private Double currentProgress;

    // Constructor for basic metadata (Used when loading from OrientDB)
    public Item(String id, String name, boolean active, Map<String, Object> properties) {
        this.id = id;
        this.name = name;
        this.active = active;
        this.properties = properties;
    }

    // Constructor from Event
    public Item(ItemCreatedEvent event) {
        this.id = event.getEntityId();
        this.name = event.getName();
        this.active = event.isActive();
        this.properties = event.getProperties();
    }

    public void resume() {
        this.active = true;
    }

    public void stop() {
        this.active = false;
    }

    /**
     * Updates the item's position using IDs.
     * 
     * @param edgeId The ID of the Conveyor
     * @param nodeId The ID of the Junction (optional, can be null if unknown)
     * @param time   When the item entered this edge
     */
    public void updatePosition(String edgeId, String nodeId, Instant time) {
        this.currentEdgeId = edgeId;
        this.lastNodeId = nodeId;
        this.entryTimestamp = time;
        this.currentProgress = 0.0;
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