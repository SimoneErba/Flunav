package com.fiumen.backend.models.graph;

import java.util.List;

import com.fiumen.backend.models.response.ConveyorResponse;
import com.fiumen.backend.models.response.ItemResponse;
import com.fiumen.backend.models.response.LocationResponse;

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
}