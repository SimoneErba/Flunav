package com.flunav.backend.models.response;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import flunav.types.RoutingStatus;
import flunav.types.PositionType;
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
    private Double priority;
    private Double effectivePriority;
    private Boolean rushActive;
    private Map<String, Object> properties;

    // --- POSITIONING (Physics) ---
    private String locationId;
    private String currentEdgeId; // The Conveyor ID
    private Instant entryTimestamp; // When it entered the edge

    // Calculated Progress (0.0 to 1.0) sent on initial load
    // The frontend uses this + speed to start animation
    private Double progress;

    // --- NAVIGATION ---
    private List<String> destinations;
    private String selectedExitId;
    private RoutingStatus routingStatus;
    private Instant routingStatusUpdatedAt;
    private List<String> path; // Ordered location IDs
    private String customColor;
    private String customBorderColor;
    private Double customBorderWidth;
    private String plannedPositionId;
    private PositionType plannedPositionType;
    private Instant plannedTransitionTimestamp;
    private Integer stagingOrder;
    private Boolean flowPaused;
    private Instant movementCheckTimestamp;

    public ItemResponse(String id, String name, Boolean active, Double priority, Map<String, Object> properties,
            String locationId, String currentEdgeId, Instant entryTimestamp, Double progress,
            List<String> destinations, String selectedExitId, RoutingStatus routingStatus,
            Instant routingStatusUpdatedAt, List<String> path, String customColor, String customBorderColor,
            Double customBorderWidth, String plannedPositionId, PositionType plannedPositionType,
            Instant plannedTransitionTimestamp, Integer stagingOrder) {
        this(id, name, active, priority, priority, false, properties, locationId, currentEdgeId, entryTimestamp,
                progress, destinations, selectedExitId, routingStatus, routingStatusUpdatedAt, path, customColor,
                customBorderColor, customBorderWidth, plannedPositionId, plannedPositionType, plannedTransitionTimestamp,
                stagingOrder, false, null);
    }

    public ItemResponse(String id, String name, Boolean active, Map<String, Object> properties,
            String locationId, String currentEdgeId, Instant entryTimestamp, Double progress,
            List<String> destinations, String selectedExitId, RoutingStatus routingStatus,
            Instant routingStatusUpdatedAt, List<String> path, String customColor) {
        this(id, name, active, 0.0, 0.0, false, properties, locationId, currentEdgeId, entryTimestamp, progress,
                destinations, selectedExitId, routingStatus, routingStatusUpdatedAt, path, customColor, null, null,
                null, null, null, null, false, null);
    }
}
