package com.flonav.backend.domain;

import flonav.types.ConveyorType;
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
    private ConveyorType type;
    private boolean active;
    private Integer capacity;
    private boolean isMainPath;

    public Conveyor(String id, String sourceId, String targetId, Double length, Double speed, ConveyorType type,
            boolean active, Integer capacity, boolean isMainPath) {
        this.id = id;
        this.sourceLocationId = sourceId;
        this.targetLocationId = targetId;
        this.length = length;
        this.speed = speed;
        this.type = type;
        this.active = active;
        this.capacity = capacity;
        this.isMainPath = isMainPath;
    }
}