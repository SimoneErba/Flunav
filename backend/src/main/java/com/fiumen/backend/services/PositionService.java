package com.fiumen.backend.services;

import com.fiumen.backend.utils.OrientDBUtils;
import com.orientechnologies.orient.core.db.ODatabaseSession;
import com.orientechnologies.orient.core.record.ODirection;
import com.orientechnologies.orient.core.record.OElement;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class PositionService {
    private final OrientDBService orientDBService;

    @Autowired
    public PositionService(OrientDBService orientDBService) {
        this.orientDBService = orientDBService;
    }

    public void createConnection(String itemId, String locationId) {
        try (ODatabaseSession db = orientDBService.getSession()) {
            OElement item = OrientDBUtils.loadAndValidateVertexByCustomId(db, itemId);
            OElement location = OrientDBUtils.loadAndValidateVertexByCustomId(db, locationId);

            item.asVertex().get().addEdge(location.asVertex().get(), "HasPosition");
            item.save();
        }
    }

    public void moveConnection(String itemId, String newLocationId) {
        try (ODatabaseSession db = orientDBService.getSession()) {
            OElement item = OrientDBUtils.loadAndValidateVertexByCustomId(db, itemId);
            OElement newLocation = OrientDBUtils.loadAndValidateVertexByCustomId(db, newLocationId);

            var edges = item.asVertex().get().getEdges(ODirection.OUT, "HasPosition");
            for (var edge : edges) {
                edge.delete();
            }
            item.asVertex().get().addEdge(newLocation.asVertex().get(), "HasPosition");
            item.save();
        }
    }

    public void deleteConnections(String itemId) {
        try (ODatabaseSession db = orientDBService.getSession()) {
            OElement item = OrientDBUtils.loadAndValidateVertexByCustomId(db, itemId);

            var edges = item.asVertex().get().getEdges(ODirection.OUT, "HasPosition");
            for (var edge : edges) {
                edge.delete();
            }
        }
    }
}
