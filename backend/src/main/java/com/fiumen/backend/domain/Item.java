package com.fiumen.backend.domain;

import lombok.Getter;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import fiumen.events.ItemCreatedEvent;
import fiumen.events.ItemPropertiesUpdatedEvent;

@Getter
public class Item {
    private final String id;
    private String name;
    private Double speed;
    private boolean active;
    private Map<String, Object> properties;
    private Location location;
    private String destination;
    private List<String> path;
    private ProgressInfo progressInfo;
    private Instant lastKnownLocationTimestamp;

    public Item(String id, String name, Double speed, boolean active, Map<String, Object> properties,
            Location location, Instant time) {
        this.id = id;
        this.name = name;
        this.speed = speed;
        this.active = active;
        this.properties = properties;
        this.location = location;
        this.lastKnownLocationTimestamp = time;
    }

    public Item(String id, String name, Double speed, boolean active, Map<String, Object> properties) {
        this.id = id;
        this.name = name;
        this.speed = speed;
        this.active = active;
        this.properties = properties;
    }

    public Item(ItemCreatedEvent event) {
        this.id = event.getEntityId();
        this.name = event.getName();
        this.speed = event.getSpeed();
        this.active = event.isActive();
        this.properties = event.getProperties();
    }

    public void resume() {
        if (!this.active) {
            this.active = true;
        }
    }

    public void stop() {
        if (this.active) {
            this.active = false;
        }
    }

    public void setPath(List<String> path) {
        this.path = path;
    }

    public void setDestination(String destination) {
        this.destination = destination;
    }

    public void updatePosition(Location newLocation, Instant time) {
        if (this.location == null) {
            this.location = newLocation;
            this.lastKnownLocationTimestamp = time;
            return;
        }
    }

    public void updateName(String name) {
        this.name = name;
    }

    public void updateSpeed(double speed, Instant time) {
        Duration timeDelta = Duration.between(this.progressInfo.getDatetime(), time);
        double milliSecondsElapsed = timeDelta.toMillis();

        double progressDelta = milliSecondsElapsed * this.speed / this.location.getLength();
        double newProgress = this.progressInfo.getProgress() + progressDelta;

        this.progressInfo = new ProgressInfo(newProgress, time);

        this.speed = speed;
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