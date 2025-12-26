package com.flunav.backend.models.graph;

import java.time.Instant;
import java.util.List;

import com.flunav.backend.models.response.ConveyorResponse;
import com.flunav.backend.models.response.ItemResponse;
import com.flunav.backend.models.response.LocationResponse;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class GraphData {
    // The Nodes (Waypoints)
    private List<LocationResponse> locations;

    // The Edges (Physics) - Renamed from 'connections'
    private List<ConveyorResponse> conveyors;

    // The Live State - Moved to top level (was nested in locations)
    private List<ItemResponse> items;
    private Instant timestamp;
}