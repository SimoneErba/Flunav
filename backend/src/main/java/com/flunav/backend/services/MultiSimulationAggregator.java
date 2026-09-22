package com.flunav.backend.services;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;
import java.util.function.Function;

import org.springframework.stereotype.Service;

import com.flunav.backend.models.multisimulation.MetricDistribution;
import com.flunav.backend.models.multisimulation.MultiSimulationReport;
import com.flunav.backend.models.multisimulation.MultiSimulationRun;
import com.flunav.backend.models.multisimulation.MultiSimulationRunMetrics;
import com.flunav.backend.models.multisimulation.MultiSimulationRunStatus;

@Service
public class MultiSimulationAggregator {

    public MultiSimulationReport aggregate(String id, List<MultiSimulationRun> runs, Instant generatedAt) {
        List<MultiSimulationRunMetrics> completed = runs.stream()
                .filter(run -> run.status() == MultiSimulationRunStatus.COMPLETED && run.metrics() != null)
                .map(MultiSimulationRun::metrics)
                .toList();
        Map<String, MetricDistribution> metrics = new LinkedHashMap<>();
        add(metrics, "throughputPerHour", completed, MultiSimulationRunMetrics::throughputPerHour);
        add(metrics, "averageJourneyTimeSeconds", completed, MultiSimulationRunMetrics::averageJourneyTimeSeconds);
        add(metrics, "p95JourneyTimeSeconds", completed, MultiSimulationRunMetrics::p95JourneyTimeSeconds);
        add(metrics, "recirculationRatePercent", completed, MultiSimulationRunMetrics::recirculationRatePercent);
        add(metrics, "itemsCompleted", completed, value -> value.itemsCompleted());
        add(metrics, "itemsRemaining", completed, value -> value.itemsRemaining());
        add(metrics, "maximumSystemPopulation", completed, value -> value.maximumSystemPopulation());
        add(metrics, "conveyorFailureCount", completed, value -> value.conveyorFailureCount());
        aggregateMapMetrics(metrics, "destination", "generated", completed,
                MultiSimulationRunMetrics::generatedByDestination);
        aggregateMapMetrics(metrics, "destination", "completed", completed,
                MultiSimulationRunMetrics::completedByDestination);
        aggregateMapMetrics(metrics, "conveyor", "failures", completed,
                MultiSimulationRunMetrics::failuresByConveyor);
        aggregateMapMetrics(metrics, "conveyor", "downtimePercent", completed,
                MultiSimulationRunMetrics::conveyorDowntimePercent);

        int failed = (int) runs.stream().filter(run -> run.status() == MultiSimulationRunStatus.FAILED).count();
        int cancelled = (int) runs.stream().filter(run -> run.status() == MultiSimulationRunStatus.CANCELLED).count();
        return new MultiSimulationReport(id, completed.size(), failed, cancelled, metrics, generatedAt);
    }

    private void add(
            Map<String, MetricDistribution> target,
            String name,
            List<MultiSimulationRunMetrics> metrics,
            ToDoubleFunction<MultiSimulationRunMetrics> extractor) {
        target.put(name, distribution(metrics.stream().mapToDouble(extractor).boxed().toList()));
    }

    private void aggregateMapMetrics(
            Map<String, MetricDistribution> target,
            String scope,
            String metricName,
            List<MultiSimulationRunMetrics> runs,
            Function<MultiSimulationRunMetrics, ? extends Map<String, ? extends Number>> extractor) {
        java.util.Set<String> keys = runs.stream()
                .flatMap(run -> extractor.apply(run).keySet().stream())
                .collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));
        for (String key : keys) {
            List<Double> values = runs.stream()
                    .map(run -> extractor.apply(run).get(key))
                    .map(value -> value == null ? 0.0 : value.doubleValue())
                    .toList();
            target.put(scope + "." + key + "." + metricName, distribution(values));
        }
    }

    public MetricDistribution distribution(List<Double> values) {
        if (values.isEmpty()) {
            return new MetricDistribution(0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Double::compareTo);
        double mean = sorted.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double variance = sorted.stream().mapToDouble(value -> {
            double delta = value - mean;
            return delta * delta;
        }).average().orElse(0);
        return new MetricDistribution(
                sorted.size(),
                mean,
                percentile(sorted, 0.50),
                Math.sqrt(variance),
                sorted.getFirst(),
                sorted.getLast(),
                percentile(sorted, 0.05),
                percentile(sorted, 0.25),
                percentile(sorted, 0.75),
                percentile(sorted, 0.95));
    }

    private double percentile(List<Double> sorted, double probability) {
        if (sorted.size() == 1) {
            return sorted.getFirst();
        }
        double index = probability * (sorted.size() - 1);
        int lower = (int) Math.floor(index);
        int upper = (int) Math.ceil(index);
        if (lower == upper) {
            return sorted.get(lower);
        }
        double fraction = index - lower;
        return sorted.get(lower) + fraction * (sorted.get(upper) - sorted.get(lower));
    }
}
