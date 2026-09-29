package com.flunav.backend.services;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flunav.backend.context.DatabaseContextHolder;
import com.flunav.backend.models.graph.GraphData;
import com.flunav.backend.models.multisimulation.MultiSimulationBaseline;
import com.flunav.backend.models.response.ConveyorResponse;
import com.flunav.backend.models.response.LocationResponse;
import com.flunav.backend.models.scenario.ScenarioDocument;
import com.flunav.backend.models.scenario.SavedScenario;
import com.flunav.backend.models.simulation.SimulationStatus;
import com.flunav.backend.repositories.ScenarioRepository;
import flunav.events.*;

@Service
public class ScenarioService {
    private static final Map<String, String> UNITS = Map.of("length", "m", "speed", "m/s",
            "time", "s", "itemLengthProperty", "m", "coordinates", "visual");
    private final ScenarioRepository repository;
    private final GraphService graph;
    private final EventProcessor processor;
    private final SimulationService simulations;
    private final DestinationMappingService destinations;
    private final DestinationExitMappingService exits;
    private final DisplayRulesService rules;
    private final MultiSimulationService experiments;
    private final TimeService time;
    private final ObjectMapper mapper;
    private final int maximumBytes;
    private final double defaultItemLengthCm;
    @Value("${app.version:0.0.1-SNAPSHOT}") private String applicationVersion;

    public ScenarioService(ScenarioRepository repository, GraphService graph, EventProcessor processor,
            SimulationService simulations, DestinationMappingService destinations,
            DestinationExitMappingService exits, DisplayRulesService rules, MultiSimulationService experiments,
            TimeService time, ObjectMapper mapper, @Value("${scenario.max-bytes:10485760}") int maximumBytes,
            @Value("${average-item-length-cm:15}") double defaultItemLengthCm) {
        this.repository = repository;
        this.graph = graph;
        this.processor = processor;
        this.simulations = simulations;
        this.destinations = destinations;
        this.exits = exits;
        this.rules = rules;
        this.experiments = experiments;
        this.time = time;
        this.mapper = mapper;
        this.maximumBytes = maximumBytes;
        this.defaultItemLengthCm = defaultItemLengthCm;
    }

    /** Captures effective configuration in the same storage context as the graph. */
    public ScenarioDocument capture(String name, String description, boolean includeItems) {
        String simulationId = DatabaseContextHolder.getSimulationId();
        if (simulationId != null) {
            var state = simulations.getSimulationState(simulationId);
            synchronized (state.getExecutionLock()) {
                if (state.getStatus() != SimulationStatus.PAUSED && state.getStatus() != SimulationStatus.READY
                        && state.getStatus() != SimulationStatus.STOPPED) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Pause the simulation before exporting");
                }
                return captureAt(name, description, includeItems, simulations.getSimulationClock(simulationId));
            }
        }
        return processor.withLiveSnapshotBarrier(() -> captureAt(name, description, includeItems, time.now()));
    }

    private ScenarioDocument captureAt(String name, String description, boolean includeItems, Instant start) {
        GraphData current = includeItems ? graph.getGraphData(start, false) : graph.getTopologyData();
        GraphData baseline = new GraphData(current.getLocations(), current.getConveyors(),
                includeItems ? current.getItems() : List.of(), current.getSensorMappings(), start);
        return validate(new ScenarioDocument("flusim", 1, "1", "legacy", name, description,
                time.physicalNow(), applicationVersion, UNITS, portableGraph(baseline),
                new ScenarioDocument.Configuration(destinations.getDestinationMappings(), exits.getMappings(),
                        rules.getDisplayRules()), null, null));
    }

    public ScenarioDocument parse(byte[] bytes) {
        if (bytes.length > maximumBytes) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Scenario file is too large");
        try {
            return validate(mapper.readValue(bytes, ScenarioDocument.class));
        } catch (ResponseStatusException failure) {
            throw failure;
        } catch (Exception failure) {
            throw bad("Malformed scenario JSON: " + failure.getMessage());
        }
    }

    /** Validation performs no storage writes or runtime allocation. Hashes are recomputed from canonical content. */
    public ScenarioDocument validate(ScenarioDocument document) {
        try {
            require(document != null && "flusim".equals(document.format()) && document.schemaVersion() == 1
                    && "1".equals(document.modelVersion()) && List.of("legacy", "1", "2").contains(document.generatorVersion()), "Unsupported scenario version");
            require(document.name() != null && !document.name().isBlank(), "Scenario name is required");
            require(UNITS.equals(document.units()), "Unsupported units");
            require(document.exportedAt() != null, "Export timestamp is required");
            GraphData baseline = document.baseline();
            require(baseline != null && baseline.getTimestamp() != null && baseline.getLocations() != null
                    && baseline.getConveyors() != null && baseline.getItems() != null
                    && baseline.getSensorMappings() != null, "Complete baseline and virtual start time are required");
            Set<String> ids = new HashSet<>();
            Set<String> locations = new HashSet<>();
            for (var location : baseline.getLocations()) {
                require(location != null, "Null location");
                unique(ids, location.getId()); locations.add(location.getId());
                if (location.getActiveAlarms() == null) location.setActiveAlarms(List.of());
                require(location.getType() != null && location.getActive() != null, "Location type and active state are required");
                finite(location.getLatitude(), "latitude"); finite(location.getLongitude(), "longitude");
                require(location.getCapacity() == null || location.getCapacity() > 0, "Location capacity must be positive");
                require(location.getTimeToProcessMs() == null || location.getTimeToProcessMs() >= 0, "Processing delay must be non-negative");
            }
            Set<String> conveyors = new HashSet<>();
            for (var conveyor : baseline.getConveyors()) {
                require(conveyor != null, "Null conveyor");
                unique(ids, conveyor.getId()); conveyors.add(conveyor.getId());
                if (conveyor.getActiveAlarms() == null) conveyor.setActiveAlarms(List.of());
                if (conveyor.getFlowStopped() == null) conveyor.setFlowStopped(false);
                if (conveyor.getMinDistance() == null) conveyor.setMinDistance(conveyor.getType() == flunav.types.ConveyorType.STAGING ? 0.1 : 0.0);
                require(locations.contains(conveyor.getSourceId()) && locations.contains(conveyor.getTargetId()), "Unknown conveyor endpoint");
                finite(conveyor.getLength(), "length"); finite(conveyor.getSpeed(), "speed"); finite(conveyor.getMinDistance(), "minimum distance");
                require(conveyor.getLength() > 0 && conveyor.getSpeed() >= 0 && conveyor.getMinDistance() >= 0, "Invalid conveyor physics");
                require(conveyor.getType() != null && conveyor.getActive() != null, "Conveyor type and active state are required");
                require(conveyor.getCapacity() == null || conveyor.getCapacity() > 0, "Conveyor capacity must be positive");
            }
            Set<String> sensors = new HashSet<>();
            for (var sensor : baseline.getSensorMappings()) {
                require(sensor != null && sensor.getSensorName() != null && !sensor.getSensorName().isBlank()
                        && sensors.add(sensor.getSensorName().toLowerCase(java.util.Locale.ROOT))
                        && conveyors.contains(sensor.getConveyorId()), "Invalid sensor reference or duplicate sensor");
                finite(sensor.getProgress(), "sensor progress");
                require(sensor.getProgress() >= 0 && sensor.getProgress() <= 100, "Sensor progress must be between 0 and 100");
            }
            for (var item : baseline.getItems()) {
                require(item != null, "Null item"); unique(ids, item.getId());
                require(item.getProperties() != null && item.getProperties().get("lengthMeters") instanceof Number,
                        "Initial item lengthMeters is required");
                double itemLength = ((Number) item.getProperties().get("lengthMeters")).doubleValue();
                require(Double.isFinite(itemLength) && itemLength > 0, "Initial item lengthMeters must be positive and finite");
                require(item.getCurrentEdgeId() != null ? conveyors.contains(item.getCurrentEdgeId())
                        : locations.contains(item.getLocationId()), "Invalid item position");
                finite(item.getProgress(), "item progress");
                require(item.getProgress() >= 0 && item.getProgress() <= 1, "Item progress must be between 0 and 1");
                if (item.getPriority() != null) { finite(item.getPriority(), "priority"); require(item.getPriority() >= 0 && item.getPriority() <= 1, "Invalid priority"); }
                if (item.getPath() != null) require(locations.containsAll(item.getPath()), "Invalid item path");
                if (item.getSelectedExitId() != null) require(locations.contains(item.getSelectedExitId()), "Invalid selected exit");
            }
            var configuration = document.configuration();
            require(configuration != null && configuration.destinations() != null && configuration.exits() != null
                    && configuration.displayRules() != null, "Complete configuration is required");
            destinations.normalizeAndValidate(new MapDestinationsEvent(null, configuration.destinations(), baseline.getTimestamp()));
            exits.normalizeAndValidate(new MapDestinationExitsEvent(configuration.exits(), baseline.getTimestamp()));
            rules.validateRules(configuration.displayRules());
            for (var mapping : configuration.exits()) require(locations.containsAll(mapping.getExits()), "Unknown configured exit");
            if (document.experiment() != null) {
                require(document.experiment().baseSeed() != null && baseline.getTimestamp().equals(document.experiment().simulationStartTime()), "Resolved seed and matching virtual start are required");
                experiments.validate(document.experiment(), baseline, configuration.exits());
            }
            require(mapper.writeValueAsBytes(document).length <= maximumBytes, "Scenario exceeds maximum size");
            validateNumbers(mapper.valueToTree(document));
            var topology = Map.of("locations", baseline.getLocations().stream().sorted(Comparator.comparing(LocationResponse::getId)).toList(),
                    "conveyors", baseline.getConveyors().stream().sorted(Comparator.comparing(ConveyorResponse::getId)).toList(),
                    "sensors", baseline.getSensorMappings().stream().sorted(Comparator.comparing(SensorMappingRecord::getSensorName)).toList());
            var previous = document.provenance();
            var provenance = new ScenarioDocument.Provenance(hash(topology), hash(configuration),
                    previous == null ? null : previous.templateId(), previous == null ? null : previous.templateVersion());
            return new ScenarioDocument(document.format(), document.schemaVersion(), document.modelVersion(), document.generatorVersion(),
                    document.name().trim(), document.description(), document.exportedAt(), document.applicationVersion(),
                    document.units(), baseline, configuration, document.experiment(), provenance, document.results());
        } catch (ResponseStatusException failure) {
            throw failure;
        } catch (Exception failure) {
            throw bad(failure.getMessage());
        }
    }

    public synchronized SavedScenario save(ScenarioDocument document, String id) {
        ScenarioDocument validated = validate(document);
        int revision = id == null ? 1 : get(id, null).revision() + 1;
        SavedScenario saved = new SavedScenario(id == null ? UUID.randomUUID().toString() : id, revision,
                validated.name(), validated.description(), time.physicalNow(), validated, id != null && get(id, null).reusableTemplate());
        repository.insert(saved);
        return saved;
    }

    public synchronized SavedScenario saveReusableTemplate(String id, Integer revision) {
        var source = get(id, revision);
        var document = source.document();
        var baseline = document.baseline();
        var empty = new GraphData(baseline.getLocations(), baseline.getConveyors(), List.of(), baseline.getSensorMappings(), baseline.getTimestamp());
        var experiment = document.experiment();
        if (experiment != null) experiment = new com.flunav.backend.models.multisimulation.MultiSimulationConfiguration(
                experiment.name(), experiment.simulationDurationSeconds(), experiment.numberOfRuns(), experiment.arrival(),
                experiment.sourceLocationId(), experiment.destinations(), experiment.conveyorFailures(), experiment.baseSeed(),
                experiment.simulationStartTime(), false, experiment.inputGeneratorVersion());
        var previous = document.provenance();
        var validated = validate(new ScenarioDocument(document.format(), document.schemaVersion(), document.modelVersion(), document.generatorVersion(),
                document.name(), document.description(), time.physicalNow(), document.applicationVersion(), document.units(), empty,
                document.configuration(), experiment, new ScenarioDocument.Provenance(previous.topologyHash(), previous.configurationHash(),
                        "custom:" + id, get(id, null).revision() + 1)));
        var saved = new SavedScenario(id, get(id, null).revision() + 1, validated.name(), validated.description(), time.physicalNow(), validated, true);
        repository.insert(saved);
        return saved;
    }

    public SavedScenario get(String id, Integer revision) {
        return repository.find(id, revision).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Scenario revision not found"));
    }

    public List<SavedScenario> list() { return repository.list(); }

    public com.flunav.backend.models.simulation.SimulationState open(String id, Integer revision) {
        return simulations.openScenario(toBaseline(validate(get(id, revision).document())));
    }

    public com.flunav.backend.models.multisimulation.MultiSimulation createExperiment(String id, Integer revision) {
        var document = validate(get(id, revision).document());
        require(document.experiment() != null, "Scenario has no experiment definition");
        return experiments.createFromBaseline(document.experiment(), toBaseline(document));
    }

    /** Exports the immutable starting baseline; terminal runtime state is never consulted. */
    public ScenarioDocument exportExperiment(String id, boolean includeResults) {
        var experiment = experiments.get(id);
        var baseline = experiment.baseline();
        List<DestinationMappingRecord> destinationRules = List.of();
        List<DestinationExitMappingRecord> exitRules = List.of();
        List<flunav.types.DisplayRule> displayRules = List.of();
        for (var event : baseline.configurationEvents()) {
            if (event instanceof MapDestinationsEvent value) destinationRules = value.getMappings();
            if (event instanceof MapDestinationExitsEvent value) exitRules = value.getMappings();
            if (event instanceof MapDisplayRulesEvent value) displayRules = value.getRules();
        }
        String version = experiment.configuration().inputGeneratorVersion();
        var result = validate(new ScenarioDocument("flusim", 1, "1", version == null ? "legacy" : version,
                experiment.configuration().name(), "Immutable experiment baseline", time.physicalNow(), applicationVersion, UNITS,
                portableGraph(baseline.graph()), new ScenarioDocument.Configuration(destinationRules, exitRules, displayRules),
                experiment.configuration(), null));
        ScenarioDocument.ArchivedResults archived = includeResults ? new ScenarioDocument.ArchivedResults(
                experiments.runs(id), storageReport(id), "Fixed horizon; journey times cover completed items; recirculation is passes per 100 completions") : null;
        return new ScenarioDocument(result.format(), result.schemaVersion(), result.modelVersion(), result.generatorVersion(),
                result.name(), result.description(), result.exportedAt(), result.applicationVersion(), result.units(),
                result.baseline(), result.configuration(), result.experiment(), result.provenance(), archived);
    }

    private com.flunav.backend.models.multisimulation.MultiSimulationReport storageReport(String id) {
        try { return experiments.report(id); }
        catch (ResponseStatusException failure) {
            if (failure.getStatusCode() == HttpStatus.CONFLICT) return null;
            throw failure;
        }
    }

    public MultiSimulationBaseline toBaseline(ScenarioDocument document) {
        Instant start = document.baseline().getTimestamp();
        var config = document.configuration();
        return new MultiSimulationBaseline(runtimeGraph(document.baseline()), List.of(
                new MapDestinationsEvent(null, config.destinations(), start),
                new MapDestinationExitsEvent(config.exits(), start), new MapDisplayRulesEvent(config.displayRules(), start),
                new MapSensorMappingsEvent(document.baseline().getSensorMappings(), start)),
                start, document.provenance().topologyHash(), document.provenance().configurationHash());
    }

    /** Converts the existing centimeter-based metadata into an explicit SI length on a detached DTO copy. */
    private GraphData portableGraph(GraphData source) {
        GraphData copy = mapper.convertValue(source, GraphData.class);
        for (var item : copy.getItems()) {
            Map<String, Object> properties = new java.util.HashMap<>(item.getProperties() == null ? Map.of() : item.getProperties());
            double centimeters = defaultItemLengthCm;
            for (String key : List.of("lengthCm", "length")) {
                try {
                    double value = Double.parseDouble(String.valueOf(properties.get(key)));
                    if (Double.isFinite(value) && value > 0) { centimeters = value; break; }
                } catch (NumberFormatException ignored) { }
            }
            properties.remove("lengthCm"); properties.remove("length");
            properties.put("lengthMeters", centimeters / 100.0);
            item.setProperties(properties);
        }
        return copy;
    }

    private GraphData runtimeGraph(GraphData source) {
        GraphData copy = mapper.convertValue(source, GraphData.class);
        for (var item : copy.getItems()) {
            Map<String, Object> properties = new java.util.HashMap<>(item.getProperties());
            double meters = ((Number) properties.remove("lengthMeters")).doubleValue();
            properties.put("lengthCm", meters * 100.0);
            item.setProperties(properties);
        }
        return copy;
    }

    private String hash(Object value) throws Exception {
        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(canonical(mapper.valueToTree(value)).getBytes(StandardCharsets.UTF_8)));
    }

    private String canonical(JsonNode node) {
        if (node.isObject()) {
            java.util.TreeMap<String, String> fields = new java.util.TreeMap<>();
            node.fields().forEachRemaining(entry -> fields.put(entry.getKey(), canonical(entry.getValue())));
            return fields.entrySet().stream().map(entry -> mapper.valueToTree(entry.getKey()) + ":" + entry.getValue())
                    .collect(java.util.stream.Collectors.joining(",", "{", "}"));
        }
        if (node.isArray()) {
            List<String> values = new java.util.ArrayList<>(); node.forEach(child -> values.add(canonical(child)));
            return String.join(",", values).transform(value -> "[" + value + "]");
        }
        return node.toString();
    }

    private void validateNumbers(JsonNode node) {
        if (node.isNumber()) require(Double.isFinite(node.doubleValue()), "Numeric values must be finite");
        node.forEach(this::validateNumbers);
    }
    private void unique(Set<String> ids, String id) { require(id != null && !id.isBlank() && ids.add(id), "Entity ids must be unique and nonempty"); }
    private void finite(Double value, String label) { require(value != null && Double.isFinite(value), label + " must be finite"); }
    private void require(boolean condition, String message) { if (!condition) throw bad(message); }
    private ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
}
