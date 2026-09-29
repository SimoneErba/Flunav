package com.flunav.backend.services;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.LinkedHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import com.flunav.backend.models.comparison.SimulationComparison;
import com.flunav.backend.models.multisimulation.*;
import com.flunav.backend.models.scenario.ScenarioDocument;

@Service
public class SimulationComparisonService {
    private final ClickHouseService storage;
    private final ScenarioService scenarios;
    private final MultiSimulationService experiments;
    private final TimeService time;
    private final int maximumRuns;
    private final Map<Integer, Double> criticalValues = new java.util.concurrent.ConcurrentHashMap<>();
    public SimulationComparisonService(ClickHouseService storage, ScenarioService scenarios,
            MultiSimulationService experiments, TimeService time, @Value("${comparison.max-total-runs:1000}") int maximumRuns) {
        this.storage = storage; this.scenarios = scenarios; this.experiments = experiments; this.time = time;
        this.maximumRuns = maximumRuns;
    }

    /** Every alternative is validated before any experiment is persisted; all use matching controlled inputs. */
    public synchronized SimulationComparison create(SimulationComparison.Request request) {
        if (request == null || request.name() == null || request.name().isBlank() || request.alternatives() == null
                || request.alternatives().size() < 2) throw bad("A named comparison needs at least two alternatives");
        List<SimulationComparison.Alternative> alternatives = request.alternatives().stream().map(alternative -> {
            if (alternative == null || alternative.name() == null || alternative.name().isBlank()) throw bad("Alternative name is required");
            return new SimulationComparison.Alternative(alternative.name().trim(), scenarios.validate(alternative.scenario()), alternative.referenceMapping());
        }).toList();
        if (!alternatives.getFirst().referenceMapping().isEmpty()) throw bad("Reference alternative cannot contain a mapping");
        MultiSimulationConfiguration reference = alternatives.getFirst().scenario().experiment();
        if (reference == null || !"2".equals(reference.inputGeneratorVersion())) throw bad("Comparisons require generator version 2 and experiment inputs");
        if ((long) reference.numberOfRuns() * alternatives.size() > maximumRuns) throw bad("Comparison exceeds total run limit");
        java.util.Set<String> names = new java.util.HashSet<>();
        for (var alternative : alternatives) {
            if (!names.add(alternative.name())) throw bad("Alternative names must be unique");
            java.util.Set<String> referenceIds = new java.util.HashSet<>();
            alternatives.getFirst().scenario().baseline().getLocations().forEach(location -> referenceIds.add(location.getId()));
            reference.destinations().forEach(destination -> referenceIds.add(destination.destination()));
            if (!referenceIds.containsAll(alternative.referenceMapping().keySet())
                    || new java.util.HashSet<>(alternative.referenceMapping().values()).size() != alternative.referenceMapping().size()) {
                throw bad("Reference mappings must use known ids and unique alternative ids");
            }
            var config = alternative.scenario().experiment();
            if (config == null || !"2".equals(config.inputGeneratorVersion())
                    || reference.numberOfRuns() != config.numberOfRuns()
                    || reference.simulationDurationSeconds() != config.simulationDurationSeconds()
                    || !reference.simulationStartTime().equals(config.simulationStartTime())
                    || !reference.baseSeed().equals(config.baseSeed()) || !reference.arrival().equals(config.arrival())
                    || !alternative.referenceMapping().getOrDefault(reference.sourceLocationId(), reference.sourceLocationId()).equals(config.sourceLocationId())
                    || !reference.destinations().stream().map(destination -> new DestinationProbability(
                            alternative.referenceMapping().getOrDefault(destination.destination(), destination.destination()), destination.probability())).toList().equals(config.destinations())
                    || reference.includeActiveItems() != config.includeActiveItems()
                    || !alternatives.getFirst().scenario().baseline().getItems().equals(alternative.scenario().baseline().getItems())) {
                throw bad("Alternatives must share start, duration, runs, seed, source, destinations, arrival model and population policy");
            }
        }
        List<String> ids = alternatives.stream().map(alternative -> experiments.createFromBaseline(
                alternative.scenario().experiment(), scenarios.toBaseline(alternative.scenario())).id()).toList();
        var comparison = new SimulationComparison(UUID.randomUUID().toString(), request.name().trim(), time.physicalNow(), false, alternatives, ids);
        storage.saveComparison(comparison);
        return comparison;
    }

    public List<SimulationComparison> list() { return storage.getComparisons(); }
    public SimulationComparison get(String id) {
        return list().stream().filter(value -> value.id().equals(id)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Comparison not found"));
    }
    public synchronized SimulationComparison run(String id) {
        var current = get(id);
        if (current.frozen()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Comparison already started; create a new comparison to rerun");
        var frozen = new SimulationComparison(current.id(), current.name(), current.createdAt(), true, current.alternatives(), current.experimentIds());
        storage.saveComparison(frozen);
        current.experimentIds().forEach(experiments::start);
        return frozen;
    }
    public synchronized SimulationComparison cancel(String id) {
        var current = get(id);
        if (!current.frozen()) {
            current = new SimulationComparison(current.id(), current.name(), current.createdAt(), true, current.alternatives(), current.experimentIds());
            storage.saveComparison(current);
        }
        for (String experimentId : current.experimentIds()) {
            var status = experiments.get(experimentId).status();
            if (status == MultiSimulationStatus.DRAFT || status == MultiSimulationStatus.QUEUED
                    || status == MultiSimulationStatus.RUNNING) experiments.cancel(experimentId);
        }
        return current;
    }
    public List<MultiSimulation> progress(String id) { return get(id).experimentIds().stream().map(experiments::get).toList(); }

    /** Missing run rows remain visible as pending, cancelled or interrupted rather than disappearing from exports. */
    private List<MultiSimulationRun> projectRuns(String id) {
        var experiment = experiments.get(id);
        Map<Integer, MultiSimulationRun> stored = experiments.runs(id).stream()
                .collect(java.util.stream.Collectors.toMap(MultiSimulationRun::runIndex, value -> value));
        List<MultiSimulationRun> runs = new java.util.ArrayList<>();
        for (int index = 0; index < experiment.configuration().numberOfRuns(); index++) {
            var run = stored.get(index);
            if (run == null) {
                var status = experiment.status() == MultiSimulationStatus.CANCELLED ? MultiSimulationRunStatus.CANCELLED
                        : experiment.status() == MultiSimulationStatus.FAILED ? MultiSimulationRunStatus.FAILED : MultiSimulationRunStatus.PENDING;
                run = new MultiSimulationRun(id, index, experiment.baseSeed() + index, status, 0,
                        null, null, null, status == MultiSimulationRunStatus.FAILED ? experiment.error() : null);
            }
            runs.add(run);
        }
        return List.copyOf(runs);
    }

    public ComparisonReport report(String id) {
        var comparison = get(id);
        List<List<MultiSimulationRun>> runs = comparison.experimentIds().stream().map(this::projectRuns).toList();
        List<AlternativeReport> alternatives = new java.util.ArrayList<>();
        for (int index = 0; index < runs.size(); index++) {
            Map<String, PairedDifference> differences = new LinkedHashMap<>();
            java.util.Set<String> metricNames = new java.util.LinkedHashSet<>(List.of("throughputPerHour", "averageJourneyTimeSeconds", "p95JourneyTimeSeconds",
                    "itemsCompleted", "itemsRemaining", "maximumSystemPopulation", "recirculationRatePercent", "recirculationCount", "conveyorFailureCount"));
            runs.get(index).stream().filter(run -> run.metrics() != null).forEach(run -> run.metrics().conveyorDowntimePercent()
                    .keySet().stream().sorted().forEach(conveyor -> metricNames.add("conveyor." + conveyor + ".downtimePercent")));
            for (String metric : metricNames) {
                List<Double> deltas = new java.util.ArrayList<>();
                List<Double> references = new java.util.ArrayList<>();
                Map<Integer, MultiSimulationRun> reference = runs.getFirst().stream().collect(java.util.stream.Collectors.toMap(MultiSimulationRun::runIndex, value -> value));
                for (var run : runs.get(index)) {
                    var paired = reference.get(run.runIndex());
                    if (run.status() != MultiSimulationRunStatus.COMPLETED || paired == null
                            || paired.status() != MultiSimulationRunStatus.COMPLETED || run.metrics() == null || paired.metrics() == null
                            || run.seed() != paired.seed()) continue;
                    if (metric.contains("Journey") && (run.metrics().itemsCompleted() == 0 || paired.metrics().itemsCompleted() == 0)) continue;
                    double baseline = metric(paired.metrics(), metric);
                    deltas.add(metric(run.metrics(), metric) - baseline); references.add(baseline);
                }
                double mean = deltas.stream().mapToDouble(Double::doubleValue).average().orElse(0);
                double referenceMean = references.stream().mapToDouble(Double::doubleValue).average().orElse(0);
                Double lower = null; Double upper = null;
                if (deltas.size() >= 2) {
                    double variance = deltas.stream().mapToDouble(value -> (value - mean) * (value - mean)).sum() / (deltas.size() - 1);
                    double margin = studentTCritical(deltas.size() - 1) * Math.sqrt(variance / deltas.size());
                    lower = mean - margin; upper = mean + margin;
                }
                differences.put(metric, new PairedDifference(deltas.size(), comparison.alternatives().get(index).scenario().experiment().numberOfRuns() - deltas.size(),
                        deltas.isEmpty() ? null : mean, referenceMean == 0 ? null : 100 * mean / referenceMean, lower, upper));
            }
            alternatives.add(new AlternativeReport(comparison.alternatives().get(index).name(), comparison.experimentIds().get(index), runs.get(index), differences));
        }
        return new ComparisonReport(id, alternatives);
    }
    private double metric(MultiSimulationRunMetrics value, String name) {
        return switch (name) {
            case "throughputPerHour" -> value.throughputPerHour();
            case "averageJourneyTimeSeconds" -> value.averageJourneyTimeSeconds();
            case "p95JourneyTimeSeconds" -> value.p95JourneyTimeSeconds();
            case "itemsRemaining" -> value.itemsRemaining();
            case "itemsCompleted" -> value.itemsCompleted();
            case "recirculationCount" -> value.recirculationCount();
            case "conveyorFailureCount" -> value.conveyorFailureCount();
            case "maximumSystemPopulation" -> value.maximumSystemPopulation();
            case "recirculationRatePercent" -> value.recirculationRatePercent();
            default -> value.conveyorDowntimePercent().getOrDefault(name.substring("conveyor.".length(), name.length() - ".downtimePercent".length()), 0.0);
        };
    }

    /** Integrates the Student-t density after t=sqrt(df)*tan(angle), then inverts its 0.975 quantile. */
    private double studentTCritical(int degrees) {
        return criticalValues.computeIfAbsent(degrees, this::calculateStudentTCritical);
    }

    private double calculateStudentTCritical(int degrees) {
        double total = integrate(Math.PI / 2, degrees);
        double low = 0; double high = Math.PI / 2;
        for (int iteration = 0; iteration < 50; iteration++) {
            double middle = (low + high) / 2;
            if (integrate(middle, degrees) / total < .95) low = middle; else high = middle;
        }
        return Math.sqrt(degrees) * Math.tan((low + high) / 2);
    }
    private double integrate(double upper, int degrees) {
        int steps = 2048;
        double step = upper / steps;
        double sum = 1 + Math.pow(Math.cos(upper), degrees - 1);
        for (int index = 1; index < steps; index++) sum += (index % 2 == 0 ? 2 : 4) * Math.pow(Math.cos(index * step), degrees - 1);
        return sum * step / 3;
    }
    public String summaryCsv(String id) {
        StringBuilder result = new StringBuilder("alternative,metric,pairs,excluded_runs,mean_difference,percent_change,lower_95,upper_95\n");
        for (var alternative : report(id).alternatives()) for (var entry : alternative.differences().entrySet()) {
            var difference = entry.getValue();
            result.append(csvCell(alternative.name())).append(',').append(csvCell(entry.getKey())).append(',')
                    .append(difference.pairs()).append(',').append(difference.excludedRuns()).append(',')
                    .append(java.util.Objects.toString(difference.meanDifference(), "")).append(',')
                    .append(java.util.Objects.toString(difference.percentageChange(), "")).append(',')
                    .append(java.util.Objects.toString(difference.lower95(), "")).append(',')
                    .append(java.util.Objects.toString(difference.upper95(), "")).append('\n');
        }
        return result.toString();
    }

    public String csv(String id) {
        StringBuilder result = new StringBuilder("alternative,run,seed,status,items_completed,items_remaining,throughput_per_hour,mean_journey_seconds,p95_journey_seconds\n");
        for (var alternative : report(id).alternatives()) for (var run : alternative.runs()) {
            var metrics = run.metrics();
            result.append(csvCell(alternative.name())).append(',').append(run.runIndex()).append(',').append(run.seed()).append(',').append(run.status()).append(',');
            if (metrics == null) result.append(",,,,");
            else result.append(metrics.itemsCompleted()).append(',').append(metrics.itemsRemaining()).append(',').append(metrics.throughputPerHour()).append(',')
                    .append(metrics.itemsCompleted() == 0 ? "" : metrics.averageJourneyTimeSeconds()).append(',')
                    .append(metrics.itemsCompleted() == 0 ? "" : metrics.p95JourneyTimeSeconds());
            result.append('\n');
        }
        return result.toString();
    }
    private String csvCell(String text) {
        String safe = text.stripLeading();
        if (!safe.isEmpty() && "=+-@".indexOf(safe.charAt(0)) >= 0) text = "'" + text;
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }
    private ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    public record PairedDifference(int pairs, int excludedRuns, Double meanDifference, Double percentageChange, Double lower95, Double upper95) {}
    public record AlternativeReport(String name, String experimentId, List<MultiSimulationRun> runs, Map<String, PairedDifference> differences) {}
    public record ComparisonReport(String id, List<AlternativeReport> alternatives) {}
}
