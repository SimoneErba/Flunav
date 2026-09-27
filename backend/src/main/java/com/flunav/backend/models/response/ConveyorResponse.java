package com.flunav.backend.models.response;

import java.util.Map;
import java.util.List;

import flunav.types.ConveyorType;
import flunav.types.ActiveAlarm;
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
    private Boolean operatorEnabled;
    private List<ActiveAlarm> activeAlarms;
    private Boolean flowStopped;

    public ConveyorResponse(String id, String sourceId, String targetId, String name, Double length, Double speed,
            Double minDistance, ConveyorType type, Boolean active, Boolean mainPath, Integer capacity,
            Map<String, Object> properties, String customColor, Boolean operatorEnabled,
            List<ActiveAlarm> activeAlarms) {
        this(id, sourceId, targetId, name, length, speed, minDistance, type, active, mainPath, capacity,
                properties, customColor, operatorEnabled, activeAlarms, false);
    }

    public ConveyorResponse(String id, String sourceId, String targetId, String name, Double length, Double speed,
            Double minDistance, ConveyorType type, Boolean active, Boolean mainPath, Integer capacity,
            Map<String, Object> properties, String customColor) {
        this(id, sourceId, targetId, name, length, speed, minDistance, type, active, mainPath, capacity, properties,
                customColor, active, List.of(), false);
    }
}
