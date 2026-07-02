package com.flunav.backend.models.input;

import java.util.Map;

import flunav.events.LocationCreatedEvent;
import flunav.types.LocationType;
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
    private Boolean mainPath;
    private Map<String, Object> properties;
    private Long timeToProcessMs;

    public LocationInput(String id, String name, Double latitude, Double longitude, Double length, Double speed,
            LocationType type, Integer capacity, Boolean active, Boolean mainPath, Map<String, Object> properties) {
        this(id, name, latitude, longitude, length, speed, type, capacity, active, mainPath, properties, null);
    }

    public LocationInput(LocationCreatedEvent event) {
        this.id = event.getEntityId();
        this.name = event.getName();
        this.latitude = event.getLatitude();
        this.longitude = event.getLongitude();
        this.type = event.getType();
        this.active = event.getActive();
        this.capacity = event.getCapacity();
        this.properties = event.getProperties();
        this.timeToProcessMs = event.getTimeToProcessMs();
    }
}
