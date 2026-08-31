package com.flunav.backend.models.response;

import java.util.Map;
import flunav.types.LocationType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import flunav.types.ActiveAlarm;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LocationResponse {
    private String id;
    private String name;
    private LocationType type;
    private Boolean active;

    // Coordinates
    private Double latitude;
    private Double longitude;

    // Optional Capacity (for Chutes/Sinks)
    private Integer capacity;

    // Optional processing delay for timed nodes
    private Long timeToProcessMs;

    // Metadata
    private Map<String, Object> properties;
    private String customColor;
    private List<ActiveAlarm> activeAlarms;

    public LocationResponse(String id, String name, LocationType type, Boolean active, Double latitude,
            Double longitude, Integer capacity, Long timeToProcessMs, Map<String, Object> properties,
            String customColor) {
        this(id, name, type, active, latitude, longitude, capacity, timeToProcessMs, properties, customColor,
                List.of());
    }
}
