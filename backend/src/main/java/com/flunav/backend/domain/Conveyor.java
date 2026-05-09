package com.flunav.backend.domain;

import java.util.Map;

import flunav.types.ConveyorType;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class Conveyor {
    private final String id;
    private String sourceLocationId;
    private String targetLocationId;

    private Double length;
    private Double speed;
    private Double minDistance; // Optional: distance between items when accumulated
    private ConveyorType type;
    private boolean active;
    private Integer capacity;
    private boolean mainPath;
    private Map<String, Object> properties;

    public Conveyor(String id, String sourceId, String targetId, Double length, Double speed, Double minDistance,
            ConveyorType type,
            boolean active, Integer capacity, boolean mainPath, Map<String, Object> properties) {
        this.id = id;
        this.sourceLocationId = sourceId;
        this.targetLocationId = targetId;
        this.length = length;
        this.speed = speed;
        this.minDistance = minDistance;
        this.type = type;
        this.active = active;
        this.capacity = capacity;
        this.mainPath = mainPath;
        this.properties = properties;
    }
}
