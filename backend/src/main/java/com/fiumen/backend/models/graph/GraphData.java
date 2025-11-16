package com.fiumen.backend.models.graph;

import java.util.List;

import com.fiumen.backend.models.response.ConnectionResponse;
import com.fiumen.backend.models.response.LocationResponse;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class GraphData {
    private List<LocationResponse> locations;
    private List<ConnectionResponse> connections;

    public void addLocation(LocationResponse location) {
        this.locations.add(location);
    }

    public void addConnections(List<ConnectionResponse> newConnections) {
        this.connections.addAll(newConnections);
    }
}