package com.fiumen.backend.models.response;

import java.util.Map;
import fiumen.types.LocationType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

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

    // Metadata
    private Map<String, Object> properties;

    // REMOVED: length, speed, isMainPath (Moved to ConveyorResponse)
    // REMOVED: items (Moved to GraphData)
    // REMOVED: connections (Implicit in ConveyorResponse source/target)
}