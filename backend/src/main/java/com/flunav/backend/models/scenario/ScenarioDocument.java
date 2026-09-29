package com.flunav.backend.models.scenario;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.flunav.backend.models.graph.GraphData;
import com.flunav.backend.models.multisimulation.MultiSimulationConfiguration;
import flunav.events.DestinationMappingRecord;
import flunav.events.DestinationExitMappingRecord;
import flunav.types.DisplayRule;

/** Portable effective state; runtime subscriptions and scheduled queues are never serialized. */
public record ScenarioDocument(
        String format, int schemaVersion, String modelVersion, String generatorVersion,
        String name, String description, Instant exportedAt, String applicationVersion,
        Map<String, String> units, GraphData baseline, Configuration configuration,
        MultiSimulationConfiguration experiment, Provenance provenance, ArchivedResults results) {
    public ScenarioDocument(String format, int schemaVersion, String modelVersion, String generatorVersion,
            String name, String description, Instant exportedAt, String applicationVersion, Map<String, String> units,
            GraphData baseline, Configuration configuration, MultiSimulationConfiguration experiment, Provenance provenance) {
        this(format, schemaVersion, modelVersion, generatorVersion, name, description, exportedAt, applicationVersion,
                units, baseline, configuration, experiment, provenance, null);
    }
    public record ArchivedResults(List<com.flunav.backend.models.multisimulation.MultiSimulationRun> runs,
            com.flunav.backend.models.multisimulation.MultiSimulationReport report, String measurementDefinition) {}
    @io.swagger.v3.oas.annotations.media.Schema(name = "ScenarioConfiguration")
    public record Configuration(List<DestinationMappingRecord> destinations,
            List<DestinationExitMappingRecord> exits, List<DisplayRule> displayRules) {}
    public record Provenance(String topologyHash, String configurationHash, String templateId, Integer templateVersion) {}
}
