package com.flunav.backend.models.comparison;

import java.time.Instant;
import java.util.List;
import com.flunav.backend.models.scenario.ScenarioDocument;

public record SimulationComparison(String id, String name, Instant createdAt, boolean frozen,
        List<Alternative> alternatives, List<String> experimentIds) {
    public record Alternative(String name, ScenarioDocument scenario, java.util.Map<String, String> referenceMapping) {
        public Alternative(String name, ScenarioDocument scenario) { this(name, scenario, java.util.Map.of()); }
        public Alternative { referenceMapping = referenceMapping == null ? java.util.Map.of() : java.util.Map.copyOf(referenceMapping); }
    }
    public record Request(String name, List<Alternative> alternatives) {}
}
