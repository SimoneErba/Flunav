package com.flunav.backend;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flunav.backend.context.DatabaseContextHolder;
import com.flunav.backend.controllers.SimulationTemplateController;
import com.flunav.backend.domain.Role;
import com.flunav.backend.models.scenario.ScenarioDocument;
import com.flunav.backend.models.simulation.SimulationKind;
import com.flunav.backend.models.simulation.LiveInputState;
import com.flunav.backend.services.*;
import com.flunav.backend.utils.JwtUtils;

@SpringBootTest(properties = {"springwolf.enabled=false", "state-recovery.enabled=false", "graph-snapshot.enabled=false",
        "metric-snapshot.enabled=false", "simulation.capacity.max-active=20", "simulation.capacity.min-free-memory-bytes=0"})
@AutoConfigureMockMvc
class ScenarioIntegrationTests extends BaseIntegrationTest {
    @Autowired ScenarioService scenarios;
    @Autowired SimulationTemplateController templates;
    @Autowired SimulationTemplateService templateService;
    @Autowired SimulationService simulations;
    @Autowired GraphService graph;
    @Autowired ObjectMapper mapper;
    @Autowired MockMvc mvc;
    @Autowired JwtUtils jwt;
    @Autowired MultiSimulationService experiments;
    @Autowired MultiSimulationRandomGenerator generator;
    @Autowired SimulationComparisonService comparisons;
    @Autowired EventProcessor events;
    @Autowired ClickHouseService storage;

    @Autowired ConveyorPresetService presets;

    @Test
    void conveyorTemplatesPersistFailureDefaultsAndEnforceRoles() throws Exception {
        var properties = java.util.Map.<String, Object>of("failuresPerHour", .01, "repairDurationSeconds", 60);
        var input = new com.flunav.backend.models.scenario.ConveyorPreset(null, 1, "Fast roller", "Custom component",
                flunav.types.ConveyorType.ROLLER, 5, 4, .1, null, false, properties);
        var payload = mapper.writeValueAsBytes(input);
        mvc.perform(post("/api/conveyor-presets").contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/conveyor-presets").header("Authorization", "Bearer " + jwt.generateToken("reader", Role.VIEWER))
                .contentType(MediaType.APPLICATION_JSON).content(payload)).andExpect(status().isForbidden());
        var result = mvc.perform(post("/api/conveyor-presets").header("Authorization", "Bearer " + jwt.generateToken("admin", Role.ADMIN))
                .contentType(MediaType.APPLICATION_JSON).content(payload)).andExpect(status().isOk()).andReturn();
        var saved = mapper.readValue(result.getResponse().getContentAsString(), com.flunav.backend.models.scenario.ConveyorPreset.class);
        assertTrue(presets.list().stream().anyMatch(value -> value.id().equals(saved.id()) && value.properties().equals(properties)));
        var invalid = new com.flunav.backend.models.scenario.ConveyorPreset(null, 1, "Invalid", "", flunav.types.ConveyorType.BELT,
                5, 1, 0, null, false, java.util.Map.of("failuresPerHour", -1));
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> presets.save(invalid));
        var runtime = simulations.openScenario(scenarios.toBaseline(templates.getSimulationTemplate("simple-line")));
        try (var context = DatabaseContextHolder.enterSimulationContext(runtime.getId())) {
            var topology = graph.getTopologyData();
            var source = topology.getLocations().getFirst().getId();
            var target = topology.getLocations().getLast().getId();
            events.process(new flunav.events.ConnectionCreatedEvent("preset-test", source, target, saved.length(), saved.speed(),
                    saved.minDistance(), null, saved.mainPath(), saved.name(), true, saved.type(), saved.capacity(), saved.properties()), false).join();
            var conveyor = scenarios.capture("Preset round trip", "", false).baseline().getConveyors().stream()
                    .filter(value -> "preset-test".equals(value.getId())).findFirst().orElseThrow();
            assertEquals(4, conveyor.getSpeed());
            assertEquals(properties, conveyor.getProperties());
            assertEquals(flunav.types.ConveyorType.ROLLER, conveyor.getType());
        } finally { simulations.destroySimulation(runtime.getId()); }
    }

    @Test
    void comparisonSchemaUpgradesTwiceAndReportsKnownStudentTInterval() throws Exception {
        var document = templates.getSimulationTemplate("simple-line");
        var definition = comparisons.create(new com.flunav.backend.models.comparison.SimulationComparison.Request("Statistics",
                java.util.List.of(new com.flunav.backend.models.comparison.SimulationComparison.Alternative("Reference", document),
                        new com.flunav.backend.models.comparison.SimulationComparison.Alternative("=Alternative", document))));
        String schema;
        try (var input = getClass().getResourceAsStream("/init-clickhouse/018_simulation_comparisons.sql")) {
            schema = new String(java.util.Objects.requireNonNull(input).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        try (var connection = java.sql.DriverManager.getConnection(CLICKHOUSE_CONTAINER.getJdbcUrl(),
                CLICKHOUSE_CONTAINER.getUsername(), CLICKHOUSE_CONTAINER.getPassword()); var statement = connection.createStatement()) {
            statement.execute(schema); statement.execute(schema);
        }
        assertEquals(definition, comparisons.get(definition.id()));
        for (int index = 0; index < 3; index++) {
            for (int alternative = 0; alternative < 2; alternative++) {
                var metrics = mapper.convertValue(java.util.Map.of("itemsCompleted", 1, "throughputPerHour",
                        10.0 + (alternative == 0 ? 0 : index + 1)), com.flunav.backend.models.multisimulation.MultiSimulationRunMetrics.class);
                storage.saveMultiSimulationRun(new com.flunav.backend.models.multisimulation.MultiSimulationRun(
                        definition.experimentIds().get(alternative), index, 42 + index,
                        com.flunav.backend.models.multisimulation.MultiSimulationRunStatus.COMPLETED, 600,
                        java.time.Instant.now(), java.time.Instant.now(), metrics, null));
            }
        }
        var difference = comparisons.report(definition.id()).alternatives().get(1).differences().get("throughputPerHour");
        assertEquals(3, difference.pairs());
        assertEquals(2, difference.excludedRuns());
        assertEquals(2.0, difference.meanDifference(), 1e-9);
        assertEquals(2.0 - 4.3026527299 / Math.sqrt(3), difference.lower95(), 1e-6);
        assertEquals(2.0 + 4.3026527299 / Math.sqrt(3), difference.upper95(), 1e-6);
        assertTrue(comparisons.csv(definition.id()).contains("\"'=Alternative\""));
        comparisons.cancel(definition.id());
        assertTrue(comparisons.get(definition.id()).frozen());
        assertTrue(comparisons.progress(definition.id()).stream().allMatch(value -> value.status() == com.flunav.backend.models.multisimulation.MultiSimulationStatus.CANCELLED));
    }

    @Autowired org.springframework.data.redis.core.StringRedisTemplate redis;

    @Test
    void constraintsSensorsAndInitialItemsRoundTripWithoutLiveHistory() throws Exception {
        var original = templates.getSimulationTemplate("simple-line");
        var runtime = simulations.openScenario(scenarios.toBaseline(original));
        String secondId = null;
        try {
            ScenarioDocument captured;
            try (var context = DatabaseContextHolder.enterSimulationContext(runtime.getId())) {
                var timestamp = original.baseline().getTimestamp();
                events.process(new flunav.events.ConnectionConstraintsChangedEvent("belt-1", .25, null, timestamp), false).join();
                events.process(new flunav.events.MapSensorMappingsEvent(java.util.List.of(
                        new flunav.events.SensorMappingRecord("encoder", "belt-1", 40.0)), timestamp), false).join();
                events.process(new flunav.events.ItemCreatedEvent("initial-item", "Initial", 1.0, 0.0, true,
                        "belt-1", flunav.types.PositionType.CONVEYOR, 25.0, java.util.List.of("exit"),
                        java.util.Map.of("lengthCm", 20), timestamp), false).join();
                captured = scenarios.capture("Initial population", "round trip", true);
                assertEquals(.25, captured.baseline().getItems().getFirst().getProgress(), 1e-9);
                assertEquals(.2, ((Number) captured.baseline().getItems().getFirst().getProperties().get("lengthMeters")).doubleValue(), 1e-9);
                assertNull(captured.baseline().getConveyors().stream().filter(value -> value.getId().equals("belt-1")).findFirst().orElseThrow().getCapacity());
            }
            var imported = scenarios.parse(mapper.writeValueAsBytes(captured));
            var second = simulations.openScenario(scenarios.toBaseline(imported)); secondId = second.getId();
            try (var context = DatabaseContextHolder.enterSimulationContext(secondId)) {
                var restored = scenarios.capture("Initial population", "round trip", true);
                assertEquals(captured.provenance(), restored.provenance());
                assertEquals(.25, restored.baseline().getItems().getFirst().getProgress(), 1e-9);
                assertEquals("encoder", restored.baseline().getSensorMappings().getFirst().getSensorName());
            }
        } finally {
            simulations.destroySimulation(runtime.getId());
            if (secondId != null) simulations.destroySimulation(secondId);
        }
    }

    @Test
    void oversizedPreviewIsRejectedAndFailedRestoreCleansRuntimeKeys() {
        var oversized = assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> scenarios.parse(new byte[10 * 1024 * 1024 + 1]));
        assertEquals(org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE, oversized.getStatusCode());
        var baseline = scenarios.toBaseline(templates.getSimulationTemplate("simple-line"));
        var before = redis.keys("sim:*");
        var invalid = new com.flunav.backend.models.multisimulation.MultiSimulationBaseline(baseline.graph(),
                java.util.List.of(new flunav.events.ConnectionSpeedChangedEvent("missing-conveyor", 1.0, baseline.graph().getTimestamp())),
                baseline.capturedAt(), baseline.topologyVersion(), baseline.configurationVersion());
        assertThrows(RuntimeException.class, () -> simulations.openScenario(invalid));
        assertEquals(before, redis.keys("sim:*"));
        assertNull(DatabaseContextHolder.getSimulationId());
    }


    @Test
    void identicalAlternativesRunWithZeroPairedDifferences() throws Exception {
        var document = templates.getSimulationTemplate("simple-line");
        var comparison = comparisons.create(new com.flunav.backend.models.comparison.SimulationComparison.Request("Identical",
                java.util.List.of(new com.flunav.backend.models.comparison.SimulationComparison.Alternative("Reference", document),
                        new com.flunav.backend.models.comparison.SimulationComparison.Alternative("Copy", document))));
        comparisons.run(comparison.id());
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(60).toNanos();
        while (comparisons.progress(comparison.id()).stream().anyMatch(value -> value.completedAt() == null)
                && System.nanoTime() < deadline) Thread.sleep(100);
        assertTrue(comparisons.progress(comparison.id()).stream().allMatch(value -> value.status()
                == com.flunav.backend.models.multisimulation.MultiSimulationStatus.COMPLETED));
        var report = comparisons.report(comparison.id()).alternatives().get(1);
        for (var metric : report.differences().values()) {
            assertEquals(5, metric.pairs());
            assertEquals(0.0, metric.meanDifference());
            assertEquals(0.0, metric.lower95());
            assertEquals(0.0, metric.upper95());
        }
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> comparisons.run(comparison.id()));
        assertNotNull(comparisons.get(comparison.id()));
    }

    @Test
    void controlledStreamsPreserveArrivalsAndFailuresAcrossUnrelatedChanges() {
        var config = templates.getSimulationTemplate("failure-recovery").experiment();
        long selectedSeed = 42;
        var original = generator.generate(config, selectedSeed, "same");
        while (original.events().stream().noneMatch(event -> event instanceof flunav.events.ConnectionDeactivatedEvent)
                && selectedSeed < 100) original = generator.generate(config, ++selectedSeed, "same");
        var changed = new com.flunav.backend.models.multisimulation.MultiSimulationConfiguration(config.name(),
                config.simulationDurationSeconds(), config.numberOfRuns(), config.arrival(), config.sourceLocationId(),
                config.destinations(), java.util.List.of(new com.flunav.backend.models.multisimulation.ConveyorFailureConfiguration(
                        "unrelated", 50, 5L), config.conveyorFailures().getFirst()), config.baseSeed(), config.simulationStartTime(), false, "2");
        var inputs = generator.generate(changed, selectedSeed, "same");
        var before = original.events().stream().filter(event -> event instanceof flunav.events.ItemCreatedEvent).map(event -> event.getTimestamp()).toList();
        var after = inputs.events().stream().filter(event -> event instanceof flunav.events.ItemCreatedEvent).map(event -> event.getTimestamp()).toList();
        assertEquals(before, after);
        var failuresBefore = original.events().stream().filter(event -> event instanceof flunav.events.EntityEvent entity && "main-route".equals(entity.getEntityId())).map(event -> event.getTimestamp()).toList();
        var failuresAfter = inputs.events().stream().filter(event -> event instanceof flunav.events.EntityEvent entity && "main-route".equals(entity.getEntityId())).map(event -> event.getTimestamp()).toList();
        assertEquals(failuresBefore, failuresAfter);
        assertFalse(failuresBefore.isEmpty());
    }

    @Test
    void everyTeachingTemplateCompletesWithoutLiveGraph() throws Exception {
        for (var document : templates.listSimulationTemplates()) {
            var experiment = experiments.createFromBaseline(document.experiment(), scenarios.toBaseline(document));
            experiments.start(experiment.id());
            long deadline = System.nanoTime() + java.time.Duration.ofSeconds(60).toNanos();
            while (experiments.get(experiment.id()).completedAt() == null && System.nanoTime() < deadline) Thread.sleep(100);
            assertEquals(com.flunav.backend.models.multisimulation.MultiSimulationStatus.COMPLETED, experiments.get(experiment.id()).status(), document.name());
        }
    }


    @Test
    void customTemplatesFreezeRevisionsAndOpenIndependentCopies() {
        var source = scenarios.save(templates.getSimulationTemplate("simple-line"), null);
        var reusable = scenarios.saveReusableTemplate(source.id(), source.revision());
        assertTrue(reusable.reusableTemplate());
        assertTrue(reusable.document().baseline().getItems().isEmpty());
        var instance = templateService.instantiate("custom:" + reusable.id());
        try {
            assertNotEquals(reusable.id(), instance.scenario().id());
            assertFalse(instance.scenario().reusableTemplate());
            assertEquals(reusable.document().provenance(), instance.scenario().document().provenance());
        } finally { simulations.destroySimulation(instance.runtime().getId()); }
    }

    @Test
    void roundTripReopensIndependentDetachedRuntimesAndImmutableRevisions() throws Exception {
        var original = templates.getSimulationTemplate("simple-line");
        var imported = scenarios.parse(mapper.writeValueAsBytes(original));
        assertEquals(original.provenance(), imported.provenance());
        var saved = scenarios.save(imported, null);
        var copied = scenarios.save(imported, null);
        var liveBefore = mapper.writeValueAsString(graph.getTopologyData().getLocations());
        var first = scenarios.open(saved.id(), 1);
        var second = scenarios.open(copied.id(), 1);
        try {
            assertNotEquals(first.getId(), second.getId());
            assertEquals(SimulationKind.DETACHED, first.getKind());
            assertEquals(LiveInputState.FROZEN, first.getLiveInputState());
            try (var context = DatabaseContextHolder.enterSimulationContext(first.getId())) {
                assertEquals(3, graph.getTopologyData().getLocations().size());
                assertEquals(2, graph.getTopologyData().getConveyors().size());
                var export = scenarios.capture("Edited", "copy", false);
                assertEquals(original.provenance().topologyHash(), export.provenance().topologyHash());
                var revision = scenarios.save(export, saved.id());
                assertEquals(2, revision.revision());
            }
            assertEquals("Simple line", scenarios.get(saved.id(), 1).name());
            assertEquals("Edited", scenarios.get(saved.id(), null).name());
            assertEquals(liveBefore, mapper.writeValueAsString(graph.getTopologyData().getLocations()));
        } finally {
            simulations.destroySimulation(first.getId());
            simulations.destroySimulation(second.getId());
        }
    }

    @Test
    void validatesBeforeAllocationAndEnforcesAuthentication() throws Exception {
        var document = templates.getSimulationTemplate("simple-line");
        byte[] valid = mapper.writeValueAsBytes(document);
        mvc.perform(post("/api/scenarios/import/validate").contentType(MediaType.APPLICATION_JSON).content(valid))
                .andExpect(status().isUnauthorized());
        String user = jwt.generateToken("reader", Role.VIEWER);
        mvc.perform(post("/api/scenarios/import/validate").header("Authorization", "Bearer " + user)
                .contentType(MediaType.APPLICATION_JSON).content(valid)).andExpect(status().isOk());
        mvc.perform(post("/api/scenarios/import").header("Authorization", "Bearer " + user)
                .contentType(MediaType.APPLICATION_JSON).content(valid)).andExpect(status().isForbidden());
        String admin = jwt.generateToken("admin", Role.ADMIN);
        int before = scenarios.list().size();
        document.baseline().getConveyors().getFirst().setTargetId("missing");
        mvc.perform(post("/api/scenarios/import").header("Authorization", "Bearer " + admin)
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(document)))
                .andExpect(status().isBadRequest());
        assertEquals(before, scenarios.list().size());
        mvc.perform(post("/api/scenarios/import/validate").header("Authorization", "Bearer " + admin)
                .contentType(MediaType.APPLICATION_JSON).content("{broken")).andExpect(status().isBadRequest());
        var future = mapper.readTree(valid); ((com.fasterxml.jackson.databind.node.ObjectNode) future).put("schemaVersion", 2);
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> scenarios.parse(mapper.writeValueAsBytes(future)));
    }

    @Test
    void templatesValidateAndCreateExperimentsWithoutLiveTopology() {
        for (var document : templates.listSimulationTemplates()) {
            var experiment = experiments.createFromBaseline(document.experiment(), scenarios.toBaseline(document));
            assertEquals(document.baseline().getTimestamp(), experiment.configuration().simulationStartTime());
            assertEquals(document.provenance().topologyHash(), experiment.baseline().topologyVersion());
        }
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "flunav.export-openapi", matches = "true")
    void exportsOpenApiContract() throws Exception {
        var response = mvc.perform(get("/api-docs")).andExpect(status().isOk()).andReturn();
        Files.writeString(Path.of(System.getProperty("user.dir")).resolve(Files.isDirectory(Path.of("frontend")) ? "frontend/openapi.json" : "../frontend/openapi.json"), response.getResponse().getContentAsString());
    }
}
