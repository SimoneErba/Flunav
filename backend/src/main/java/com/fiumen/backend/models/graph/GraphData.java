package com.fiumen.backend.models.graph;

import java.util.ArrayList;
import java.util.List;

import com.fiumen.backend.models.response.ConnectionResponse;
import com.fiumen.backend.models.response.LocationResponse;

import lombok.Data;

@Data
public class GraphData {
    private List<LocationResponse> locations;
    private List<ConnectionResponse> connections;

    public GraphData() {
        this.locations = new ArrayList<>();
        this.connections = new ArrayList<>();
    }

    public void addLocation(LocationResponse location) {
        this.locations.add(location);
    }

    public void addConnections(List<ConnectionResponse> newConnections) {
        this.connections.addAll(newConnections);
    }
}