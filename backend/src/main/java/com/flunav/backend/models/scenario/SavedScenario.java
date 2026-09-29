package com.flunav.backend.models.scenario;

import java.time.Instant;

public record SavedScenario(String id, int revision, String name, String description,
        Instant savedAt, ScenarioDocument document, boolean reusableTemplate) {
    public SavedScenario(String id, int revision, String name, String description, Instant savedAt, ScenarioDocument document) {
        this(id, revision, name, description, savedAt, document, false);
    }
}
