package com.flunav.backend.models.response;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ItemResponse {
    private String id;
    private String name;
    private Boolean active;
    private Map<String, Object> properties;

    // --- POSITIONING (Physics) ---
    private String locationId;
    private String currentEdgeId; // The Conveyor ID
    private Instant entryTimestamp; // When it entered the edge

    // Calculated Progress (0.0 to 1.0) sent on initial load
    // The frontend uses this + speed to start animation
    private Double progress;

    // --- NAVIGATION ---
    private String destinationId;
    private List<String> path; // List of Edge IDs
}