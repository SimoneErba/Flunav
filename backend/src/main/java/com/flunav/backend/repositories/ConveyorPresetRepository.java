package com.flunav.backend.repositories;

import java.util.List;
import org.springframework.stereotype.Repository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flunav.backend.context.DatabaseContextHolder;
import com.flunav.backend.models.scenario.ConveyorPreset;
import com.flunav.backend.services.OrientDBService;
import com.orientechnologies.orient.core.record.impl.ODocument;

@Repository
public class ConveyorPresetRepository {
    private final OrientDBService database;
    private final ObjectMapper mapper;
    public ConveyorPresetRepository(OrientDBService database, ObjectMapper mapper) { this.database = database; this.mapper = mapper; }

    public void insert(ConveyorPreset preset) {
        try (var context = DatabaseContextHolder.enterSimulationContext(null)) {
            database.withTransaction(session -> {
                ODocument row = new ODocument("ConveyorPreset");
                row.setProperty("presetId", preset.id());
                try { row.setProperty("payload", mapper.writeValueAsString(preset)); }
                catch (Exception failure) { throw new IllegalStateException("Cannot serialize preset", failure); }
                session.save(row);
            });
        }
    }

    public List<ConveyorPreset> list() {
        try (var context = DatabaseContextHolder.enterSimulationContext(null); var session = database.getSession();
                var rows = session.query("SELECT payload FROM ConveyorPreset")) {
            return rows.stream().map(row -> {
                try { return mapper.readValue((String) row.getProperty("payload"), ConveyorPreset.class); }
                catch (Exception failure) { throw new IllegalStateException("Cannot read preset", failure); }
            }).toList();
        }
    }
}
