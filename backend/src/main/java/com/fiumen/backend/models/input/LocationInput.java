package com.fiumen.backend.models.input;

import java.util.Map;

import fiumen.events.LocationCreatedEvent;
import fiumen.types.LocationType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LocationInput {
    private String id;
    private String name;
    private Double latitude;
    private Double longitude;
    private Double length;
    private Double speed;
    private LocationType type;
    private Integer capacity;
    private Boolean active;
    private Boolean isMainPath;
    private Map<String, Object> properties;

    public LocationInput(LocationCreatedEvent event) {
        this.id = event.getEntityId();
        this.name = event.getName();
        this.latitude = event.getLatitude();
        this.longitude = event.getLongitude();
        this.length = event.getLength();
        this.speed = event.getSpeed();
        this.type = event.getType();
        this.active = event.getActive();
        this.isMainPath = event.getIsMainPath();
        this.capacity = event.getCapacity();
        this.properties = event.getProperties();
    }
}
