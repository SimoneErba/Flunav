package com.flunav.backend.models.response;

import java.util.Map;

import flunav.types.ConveyorType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConveyorResponse {
    private String id;
    private String sourceId; // Source Node
    private String targetId; // Target Node
    private String name;
    // Physics
    private Double length;
    private Double speed;
    private Double minDistance;

    // Visuals / Logic
    private ConveyorType type;
    private Boolean active;
    private Boolean mainPath;
    private Integer capacity;

    private Map<String, Object> properties;
    private String customColor;
}