package com.flunav.backend.services;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import com.flunav.backend.context.DatabaseContextHolder;
import com.flunav.backend.models.RedisLiveItem;
import com.flunav.backend.models.multisimulation.MultiSimulationRunMetrics;

import flunav.events.ConnectionDeactivatedEvent;
import flunav.events.ConnectionActivatedEvent;
import flunav.events.DomainEvent;
import flunav.events.ItemCreatedEvent;

@Service
public class MultiSimulationMetricsService {
    private final Map<String, Accumulator> accumulators = new ConcurrentHashMap<>();

    public void start(String simulationId) {
        start(simulationId, 0);
    }

    public void start(String simulationId, long initialActiveItems) {
        accumulators.put(simulationId, new Accumulator(initialActiveItems));
    }

    public boolean isCollectingCurrentSimulation() {
        String simulationId = DatabaseContextHolder.getSimulationId();
        return simulationId != null && accumulators.containsKey(simulationId);
    }

    public void recordSuccessfulReduction(DomainEvent event, Map<String, Object> result) {
        Accumulator accumulator = current();
        if (accumulator == null) {
            return;
        }
        if (event instanceof ItemCreatedEvent created && "CREATED".equals(result.get("status"))) {
            accumulator.recordCreated(created);
        } else if (event instanceof ConnectionDeactivatedEvent deactivated) {
            accumulator.recordFailure(deactivated.getEntityId(), deactivated.getTimestamp());
        } else if (event instanceof ConnectionActivatedEvent activated) {
            accumulator.recordRepair(activated.getEntityId(), activated.getTimestamp());
        }
    }

    public void recordSuccessfulExit(Instant timestamp, RedisLiveItem itemState) {
        Accumulator accumulator = current();
        if (accumulator == null || itemState == null) {
            return;
        }
        accumulator.recordExit(timestamp, itemState);
    }

    public void recordRecirculation() {
        Accumulator accumulator = current();
        if (accumulator != null) {
            accumulator.recirculationCount++;
        }
    }

    public MultiSimulationRunMetrics finish(String simulationId, long durationSeconds, Instant endTimestamp) {
        Accumulator accumulator = accumulators.remove(simulationId);
        if (accumulator == null) {
            throw new IllegalStateException("No metrics collector for simulation " + simulationId);
        }
        return accumulator.toMetrics(durationSeconds, endTimestamp);
    }

    public void discard(String simulationId) {
        accumulators.remove(simulationId);
    }

    private Accumulator current() {
        String simulationId = DatabaseContextHolder.getSimulationId();
        return simulationId == null ? null : accumulators.get(simulationId);
    }

    private static final class Accumulator {
        private long generated;
        private long completed;
        private long active;
        private long maximumPopulation;
        private long recirculationCount;
        private long conveyorFailureCount;
        private final List<Double> journeySeconds = new ArrayList<>();
        private final Map<String, Long> generatedByDestination = new HashMap<>();
        private final Map<String, Long> completedByDestination = new HashMap<>();
        private final Map<String, Long> failuresByConveyor = new HashMap<>();
        private final Map<String, Instant> downSinceByConveyor = new HashMap<>();
        private final Map<String, Double> downtimeSecondsByConveyor = new HashMap<>();

        private Accumulator(long initialActiveItems) {
            active = Math.max(0, initialActiveItems);
            maximumPopulation = active;
        }

        private synchronized void recordCreated(ItemCreatedEvent event) {
            generated++;
            active++;
            maximumPopulation = Math.max(maximumPopulation, active);
            if (event.getDestinations() != null) {
                event.getDestinations().forEach(destination -> generatedByDestination.merge(destination, 1L, Long::sum));
            }
        }

        private synchronized void recordExit(Instant timestamp, RedisLiveItem state) {
            completed++;
            active = Math.max(0, active - 1);
            if (state.getCreatedAt() != null && !timestamp.isBefore(state.getCreatedAt())) {
                journeySeconds.add(Duration.between(state.getCreatedAt(), timestamp).toNanos() / 1_000_000_000.0);
            }
            if (state.getDestinations() != null) {
                state.getDestinations().forEach(destination -> completedByDestination.merge(destination, 1L, Long::sum));
            }
        }

        private synchronized void recordFailure(String conveyorId, Instant timestamp) {
            if (downSinceByConveyor.putIfAbsent(conveyorId, timestamp) == null) {
                conveyorFailureCount++;
                failuresByConveyor.merge(conveyorId, 1L, Long::sum);
            }
        }

        private synchronized void recordRepair(String conveyorId, Instant timestamp) {
            Instant downSince = downSinceByConveyor.remove(conveyorId);
            if (downSince != null && timestamp.isAfter(downSince)) {
                downtimeSecondsByConveyor.merge(conveyorId,
                        Duration.between(downSince, timestamp).toNanos() / 1_000_000_000.0, Double::sum);
            }
        }

        private synchronized MultiSimulationRunMetrics toMetrics(long durationSeconds, Instant endTimestamp) {
            downSinceByConveyor.forEach((conveyorId, downSince) -> {
                if (endTimestamp.isAfter(downSince)) {
                    downtimeSecondsByConveyor.merge(conveyorId,
                            Duration.between(downSince, endTimestamp).toNanos() / 1_000_000_000.0, Double::sum);
                }
            });
            Map<String, Double> downtimePercent = new HashMap<>();
            downtimeSecondsByConveyor.forEach((conveyorId, seconds) -> downtimePercent.put(
                    conveyorId, durationSeconds == 0 ? 0 : Math.min(100.0, seconds * 100.0 / durationSeconds)));
            List<Double> sortedJourneys = new ArrayList<>(journeySeconds);
            sortedJourneys.sort(Double::compareTo);
            double average = sortedJourneys.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            double durationHours = durationSeconds / 3600.0;
            return new MultiSimulationRunMetrics(
                    generated,
                    completed,
                    active,
                    durationHours > 0 ? completed / durationHours : 0,
                    average,
                    percentile(sortedJourneys, 0.50),
                    percentile(sortedJourneys, 0.95),
                    percentile(sortedJourneys, 0.99),
                    sortedJourneys.isEmpty() ? 0 : sortedJourneys.getFirst(),
                    sortedJourneys.isEmpty() ? 0 : sortedJourneys.getLast(),
                    recirculationCount,
                    completed == 0 ? 0 : recirculationCount * 100.0 / completed,
                    maximumPopulation,
                    conveyorFailureCount,
                    generatedByDestination,
                    completedByDestination,
                    failuresByConveyor,
                    downtimePercent);
        }

        private double percentile(List<Double> sorted, double probability) {
            if (sorted.isEmpty()) {
                return 0;
            }
            if (sorted.size() == 1) {
                return sorted.getFirst();
            }
            double index = probability * (sorted.size() - 1);
            int lower = (int) Math.floor(index);
            int upper = (int) Math.ceil(index);
            double fraction = index - lower;
            return sorted.get(lower) + fraction * (sorted.get(upper) - sorted.get(lower));
        }
    }
}
