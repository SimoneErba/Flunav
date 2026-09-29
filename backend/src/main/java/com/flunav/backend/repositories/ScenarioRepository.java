package com.flunav.backend.repositories;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Repository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flunav.backend.context.DatabaseContextHolder;
import com.flunav.backend.models.scenario.SavedScenario;
import com.flunav.backend.services.OrientDBService;
import com.orientechnologies.orient.core.record.impl.ODocument;

/** Durable immutable revisions always belong to the installation's live database. */
@Repository
public class ScenarioRepository {
    private final OrientDBService database;
    private final ObjectMapper mapper;

    public ScenarioRepository(OrientDBService database, ObjectMapper mapper) {
        this.database = database;
        this.mapper = mapper;
    }

    public void insert(SavedScenario scenario) {
        try (var context = DatabaseContextHolder.enterSimulationContext(null)) {
            database.withTransaction(session -> {
                try {
                    ODocument record = new ODocument("ScenarioRevision");
                    record.setProperty("scenarioId", scenario.id());
                    record.setProperty("revision", scenario.revision());
                    record.setProperty("name", scenario.name());
                    record.setProperty("description", scenario.description());
                    record.setProperty("savedAt", scenario.savedAt().toString());
                    record.setProperty("reusableTemplate", scenario.reusableTemplate());
                    record.setProperty("payload", mapper.writeValueAsString(scenario));
                    session.save(record);
                } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
                    throw new IllegalStateException("Cannot serialize scenario revision", failure);
                }
            });
        }
    }

    public Optional<SavedScenario> find(String id, Integer revision) {
        try (var context = DatabaseContextHolder.enterSimulationContext(null);
                var session = database.getSession();
                var rows = revision == null
                        ? session.query("SELECT payload FROM ScenarioRevision WHERE scenarioId = ? ORDER BY revision DESC LIMIT 1", id)
                        : session.query("SELECT payload FROM ScenarioRevision WHERE scenarioId = ? AND revision = ?", id, revision)) {
            return rows.hasNext() ? Optional.of(decode(rows.next().getProperty("payload"))) : Optional.empty();
        }
    }

    public List<SavedScenario> list() {
        try (var context = DatabaseContextHolder.enterSimulationContext(null);
                var session = database.getSession();
                var rows = session.query("SELECT payload FROM ScenarioRevision ORDER BY revision DESC")) {
            java.util.Map<String, SavedScenario> latest = new java.util.LinkedHashMap<>();
            rows.stream().forEach(row -> {
                SavedScenario value = decode(row.getProperty("payload"));
                latest.putIfAbsent(value.id(), value);
            });
            return List.copyOf(latest.values());
        }
    }

    private SavedScenario decode(String payload) {
        try {
            return mapper.readValue(payload, SavedScenario.class);
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot read saved scenario revision", failure);
        }
    }
}
