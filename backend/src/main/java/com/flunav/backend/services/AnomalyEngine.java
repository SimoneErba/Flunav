package com.flunav.backend.services;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import com.flunav.backend.context.DatabaseContextHolder;
import com.flunav.backend.domain.Conveyor;
import com.flunav.backend.domain.Location;
import com.flunav.backend.models.analytics.AlarmPromotionState;
import com.flunav.backend.models.analytics.AnomalyDetectorType;
import com.flunav.backend.models.analytics.AnomalyFinding;
import com.flunav.backend.models.analytics.AnomalyIncident;
import com.flunav.backend.models.analytics.AnomalyNotification;
import com.flunav.backend.models.analytics.AnomalyProcessingMode;
import com.flunav.backend.models.analytics.DetectorBaseline;
import com.flunav.backend.models.analytics.LocationFlowObservation;
import com.flunav.backend.models.analytics.PositionTransitionObservation;
import com.flunav.backend.models.analytics.TransitObservation;
import com.flunav.backend.repositories.AnomalyObservationRepository;
import com.flunav.backend.repositories.LiveConveyorRepository;
import com.flunav.backend.repositories.LiveLocationRepository;
import com.flunav.backend.repositories.LiveSimulationRepository;

import flunav.events.AnomalyEvaluationTickEvent;
import flunav.events.ComponentAlarmRaisedEvent;
import flunav.types.AlarmSeverity;
import flunav.types.AlarmSource;
import flunav.types.ComponentType;
import flunav.types.LocationType;
import flunav.types.PositionType;

@Service
public class AnomalyEngine {
    private static final String VERSION = "v1";
    private static final int MINIMUM_BASELINE_SAMPLES = 30;
    private static final Duration BASELINE_WINDOW = Duration.ofDays(7);
    private static final Duration MAX_BASELINE_AGE = Duration.ofMinutes(10);

    private final AnomalyObservationRepository observations;
    private final TopologyProvider topology;
    private final LiveConveyorRepository conveyors;
    private final LiveLocationRepository locations;
    private final LiveSimulationRepository simulations;
    private final ClickHouseService clickHouse;
    private final WebSocketService webSockets;
    private final EventProcessor eventProcessor;
    private final long fastSeconds;
    private final long minuteSeconds;
    private final long baselineSeconds;

    public AnomalyEngine(AnomalyObservationRepository observations, TopologyProvider topology,
            LiveConveyorRepository conveyors, LiveLocationRepository locations,
            LiveSimulationRepository simulations, ClickHouseService clickHouse, WebSocketService webSockets,
            @Lazy EventProcessor eventProcessor,
            @Value("${anomaly.cadence.fast-seconds:10}") long fastSeconds,
            @Value("${anomaly.cadence.minute-seconds:60}") long minuteSeconds,
            @Value("${anomaly.cadence.baseline-seconds:300}") long baselineSeconds) {
        this.observations = observations;
        this.topology = topology;
        this.conveyors = conveyors;
        this.locations = locations;
        this.simulations = simulations;
        this.clickHouse = clickHouse;
        this.webSockets = webSockets;
        this.eventProcessor = eventProcessor;
        this.fastSeconds = positive(fastSeconds, "fast cadence");
        this.minuteSeconds = positive(minuteSeconds, "minute cadence");
        this.baselineSeconds = positive(baselineSeconds, "baseline cadence");
    }

    /**
     * Evaluates one idempotent virtual boundary. Observations are only read from
     * the closed interval since the preceding boundary, so late data cannot reopen
     * an alarm that was already decided at an older tick.
     */
    public List<AnomalyFinding> evaluate(AnomalyEvaluationTickEvent tick, AnomalyProcessingMode mode) {
        Instant last = observations.getLastBoundary(tick.getCadence());
        if (last != null && !tick.getTimestamp().isAfter(last)) {
            return List.of();
        }

        List<AnomalyFinding> findings = switch (tick.getCadence()) {
            case FAST -> evaluateFast(last != null ? last : tick.getTimestamp().minusSeconds(fastSeconds),
                    tick.getTimestamp(), mode);
            case MINUTE -> evaluateMinute(last != null ? last : tick.getTimestamp().minusSeconds(minuteSeconds),
                    tick.getTimestamp(), mode);
            case BASELINE -> {
                refreshBaselines(tick.getTimestamp());
                yield List.of();
            }
        };

        observations.setLastBoundary(tick.getCadence(), tick.getTimestamp());
        if (tick.getCadence() == AnomalyEvaluationTickEvent.Cadence.FAST) {
            retryPendingPromotions(tick.getTimestamp(), mode);
            Instant correlationStart = last != null ? last : tick.getTimestamp().minusSeconds(fastSeconds);
            correlatePressureFindings(observations.getFindings(correlationStart, tick.getTimestamp()),
                    tick.getTimestamp(), mode);
        }
        return findings;
    }

    public long cadenceSeconds(AnomalyEvaluationTickEvent.Cadence cadence) {
        return switch (cadence) {
            case FAST -> fastSeconds;
            case MINUTE -> minuteSeconds;
            case BASELINE -> baselineSeconds;
        };
    }

    public Instant nextBoundary(Instant after, AnomalyEvaluationTickEvent.Cadence cadence) {
        long seconds = cadenceSeconds(cadence);
        return Instant.ofEpochSecond(Math.floorDiv(after.getEpochSecond(), seconds) * seconds + seconds);
    }

    private List<AnomalyFinding> evaluateFast(Instant previousBoundary, Instant boundary,
            AnomalyProcessingMode mode) {
        List<AnomalyFinding> result = new ArrayList<>();
        List<LocationFlowObservation> flow = observations.flows(previousBoundary, boundary);
        List<PositionTransitionObservation> transitions = observations.transitions(previousBoundary, boundary);
        Set<String> departedLocations = flow.stream()
                .filter(value -> value.direction() == LocationFlowObservation.Direction.DEPARTURE)
                .map(LocationFlowObservation::locationId)
                .collect(Collectors.toSet());
        Set<String> departedConveyors = transitions.stream()
                .filter(value -> value.previousPositionType() == PositionType.CONVEYOR)
                .filter(value -> !Objects.equals(value.previousPositionId(), value.newPositionId()))
                .map(PositionTransitionObservation::previousPositionId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        evaluateOccupancy(boundary, mode, departedLocations, departedConveyors, result);
        evaluatePaths(transitions, boundary, mode, result);
        return result;
    }

    private void evaluateOccupancy(Instant boundary, AnomalyProcessingMode mode, Set<String> departedLocations,
            Set<String> departedConveyors, List<AnomalyFinding> findings) {
        for (Conveyor conveyor : topology.getAllConveyors()) {
            long occupancy = conveyors.getItemsOrderedByDistance(conveyor.getId()).size();
            evaluateOccupancyComponent(conveyor.getId(), ComponentType.CONVEYOR, conveyor.getCapacity(), occupancy,
                    departedConveyors.contains(conveyor.getId()), boundary, mode, findings);
        }
        for (Location location : topology.getAllLocations()) {
            if (!usesCapacity(location.getType())) {
                continue;
            }
            long occupancy = locations.getItemCount(location.getId());
            evaluateOccupancyComponent(location.getId(), ComponentType.LOCATION, location.getCapacity(), occupancy,
                    departedLocations.contains(location.getId()), boundary, mode, findings);
        }
    }

    private boolean usesCapacity(LocationType locationType) {
        return locationType == LocationType.CHUTE || locationType == LocationType.ACCUMULATION;
    }

    private void evaluateOccupancyComponent(String componentId, ComponentType componentType, Integer capacity,
            long occupancy, boolean departed, Instant boundary, AnomalyProcessingMode mode,
            List<AnomalyFinding> findings) {
        String stateKey = "jam:" + componentType + ":" + componentId;
        if (capacity == null || capacity <= 0) {
            Object previousState = observations.getJamStates().get(stateKey);
            observations.putJamState(stateKey, "UNSCORABLE");
            if (!"UNSCORABLE".equals(previousState)) {
                AnomalyFinding finding = withAlarmState(newFinding(AnomalyDetectorType.UNSCORABLE_CAPACITY,
                        componentId, componentType, null, null, null, List.of(),
                        Map.of("occupancy", occupancy, "reason", "NON_POSITIVE_CAPACITY"),
                        null, null, null, null, null, null, 0, null, null, boundary, boundary, mode),
                        AlarmPromotionState.NOT_APPLICABLE);
                saveFinding(finding, mode, true);
                findings.add(finding);
            }
            return;
        }

        String raw = Objects.toString(observations.getJamStates().get(stateKey), "0|0");
        if ("UNSCORABLE".equals(raw)) {
            raw = "0|0";
        }
        String[] values = raw.split("\\|");
        long previousOccupancy = Long.parseLong(values[0]);
        int consecutive = Integer.parseInt(values[1]);
        double ratio = occupancy / (double) capacity;
        if (departed || ratio < 0.9 || occupancy < previousOccupancy) {
            consecutive = 0;
        } else if (ratio >= 0.9) {
            consecutive++;
        }
        observations.putJamState(stateKey, occupancy + "|" + consecutive);

        boolean fullJam = occupancy >= capacity && consecutive >= 2;
        boolean nearFullJam = ratio >= 0.9 && consecutive >= 3;
        if (fullJam || nearFullJam) {
            Map<String, Object> metrics = new LinkedHashMap<>();
            metrics.put("occupancy", occupancy);
            metrics.put("capacity", capacity);
            metrics.put("occupancyRatio", ratio);
            metrics.put("consecutiveTicks", consecutive);
            findings.add(persistAndPromote(newFinding(AnomalyDetectorType.OCCUPANCY_JAM, componentId,
                    componentType, null, null, null, List.of(), metrics, null, null, null, null,
                    null, null, 0, null, null, boundary, boundary, mode), mode));
        }
    }

    private void evaluatePaths(List<PositionTransitionObservation> transitions, Instant boundary,
            AnomalyProcessingMode mode,
            List<AnomalyFinding> findings) {
        for (PositionTransitionObservation transition : transitions) {
            if (isValidDirectTransition(transition)) {
                continue;
            }
            List<String> route = shortestPositionRoute(transition.previousPositionId(), transition.newPositionId(), 10);
            AnomalyDetectorType detector;
            if (!route.isEmpty() && route.size() - 1 <= 3) {
                detector = AnomalyDetectorType.SKIPPED_SENSOR;
            } else if (route.isEmpty()) {
                detector = AnomalyDetectorType.INVALID_PATH;
            } else {
                continue;
            }
            List<String> intermediate = route.size() > 2 ? route.subList(1, route.size() - 1) : List.of();
            findings.add(persistAndPromote(newFinding(detector, transition.newPositionId(),
                    positionComponentType(transition.newPositionType()), transition.itemId(),
                    transition.previousPositionId(), transition.newPositionId(), intermediate,
                    Map.of("previousProgress", Objects.requireNonNullElse(transition.previousProgress(), 0.0),
                            "reportedProgress", transition.newProgress()),
                    null, null, null, null, null, null, 0, null, null, transition.timestamp(), boundary, mode), mode));
        }
    }

    private boolean isValidDirectTransition(PositionTransitionObservation transition) {
        if (transition.previousPositionId() == null || transition.newPositionId() == null) {
            return true;
        }
        if (transition.previousPositionId().equals(transition.newPositionId())) {
            return transition.previousPositionType() == PositionType.CONVEYOR
                    && (transition.previousProgress() == null
                            || transition.newProgress() >= transition.previousProgress());
        }
        return positionNeighbors(transition.previousPositionId()).contains(transition.newPositionId());
    }

    private List<AnomalyFinding> evaluateMinute(Instant previousBoundary, Instant boundary,
            AnomalyProcessingMode mode) {
        List<AnomalyFinding> findings = new ArrayList<>();
        List<TransitObservation> transit = observations.transits(previousBoundary, boundary);
        evaluateTransit(transit, boundary, mode, findings);
        evaluateFlow(previousBoundary, boundary, transit, mode, findings);
        return findings;
    }

    private void evaluateTransit(List<TransitObservation> transit, Instant boundary, AnomalyProcessingMode mode,
            List<AnomalyFinding> findings) {
        for (TransitObservation observation : transit) {
            String key = transitKey(observation.conveyorId(), observation.sourceLocationId(),
                    observation.targetLocationId());
            DetectorBaseline baseline = observations.getBaseline(key);
            if (!isUsableBaseline(baseline, observation.timestamp())) {
                continue;
            }
            double floor = Math.max(250.0, baseline.median() * 0.05);
            double z = (observation.durationMillis() - baseline.mean())
                    / Math.max(baseline.populationStddev(), floor);
            double modified = (observation.durationMillis() - baseline.median())
                    / Math.max(1.4826 * baseline.mad(), floor);
            if (z >= 3.0 && modified >= 3.5) {
                findings.add(persistAndPromote(newFinding(AnomalyDetectorType.TRANSIT_TIME_SLOW,
                        observation.conveyorId(), ComponentType.CONVEYOR, observation.itemId(), null, null, List.of(),
                        Map.of("durationMillis", observation.durationMillis()), baseline.mean(), baseline.median(),
                        baseline.populationStddev(), baseline.mad(), z, modified, baseline.sampleCount(),
                        baseline.windowStart(), baseline.windowEnd(), observation.timestamp(), boundary, mode), mode));
            }
        }
    }

    private void evaluateFlow(Instant previousBoundary, Instant boundary, List<TransitObservation> transit,
            AnomalyProcessingMode mode, List<AnomalyFinding> findings) {
        Map<String, long[]> locationCounts = new HashMap<>();
        for (LocationFlowObservation observation : observations.flows(previousBoundary, boundary)) {
            long[] counts = locationCounts.computeIfAbsent(observation.locationId(), ignored -> new long[2]);
            counts[observation.direction() == LocationFlowObservation.Direction.ARRIVAL ? 0 : 1]++;
        }
        for (Location location : topology.getAllLocations()) {
            long[] counts = locationCounts.getOrDefault(location.getId(), new long[2]);
            long pressure = counts[0] - counts[1];
            clickHouse.saveComponentFlowBucket(previousBoundary, scopeId(), simulationId(), location.getId(),
                    ComponentType.LOCATION.name(), counts[0], counts[1]);
            observations.appendThroughput("LOCATION:" + location.getId(), previousBoundary, counts[1]);
            String pressureKey = "pressure:" + location.getId();
            int consecutive = Integer.parseInt(Objects.toString(observations.getJamStates().get(pressureKey), "0"));
            consecutive = pressure >= 1 ? consecutive + 1 : 0;
            observations.putJamState(pressureKey, String.valueOf(consecutive));
            if (pressure >= 1 && consecutive >= 3) {
                findings.add(persistAndPromote(newFinding(AnomalyDetectorType.SYSTEM_PRESSURE, location.getId(),
                        ComponentType.LOCATION, null, null, null, List.of(),
                        Map.of("arrivals", counts[0], "departures", counts[1], "pressure", pressure,
                                "consecutiveBuckets", consecutive),
                        null, null, null, null, null, null, 0, null, null, previousBoundary, boundary, mode), mode));
            }
        }

        Map<String, Long> conveyorThroughput = transit.stream()
                .collect(Collectors.groupingBy(TransitObservation::conveyorId, Collectors.counting()));
        for (Conveyor conveyor : topology.getAllConveyors()) {
            long count = conveyorThroughput.getOrDefault(conveyor.getId(), 0L);
            observations.appendThroughput("CONVEYOR:" + conveyor.getId(), previousBoundary, count);
            evaluateTurbulence(conveyor.getId(), ComponentType.CONVEYOR, boundary, mode, findings);
        }
        for (Location location : topology.getAllLocations()) {
            evaluateTurbulence(location.getId(), ComponentType.LOCATION, boundary, mode, findings);
        }
    }

    private void evaluateTurbulence(String componentId, ComponentType type, Instant boundary,
            AnomalyProcessingMode mode, List<AnomalyFinding> findings) {
        String componentKey = type + ":" + componentId;
        List<Long> latest = observations.latestThroughput(componentKey, boundary.minusSeconds(1), 10);
        while (latest.size() < 10) {
            latest.add(0L);
        }
        double current = standardDeviation(latest.stream().map(Number::doubleValue).toList(), meanNumbers(latest));
        DetectorBaseline baseline = observations.getBaseline("turbulence:" + componentKey);
        if (!isUsableBaseline(baseline, boundary)) {
            return;
        }
        double floor = Math.max(0.1, baseline.median() * 0.05);
        double z = (current - baseline.mean()) / Math.max(baseline.populationStddev(), floor);
        double modified = (current - baseline.median()) / Math.max(1.4826 * baseline.mad(), floor);
        if (z >= 3.0 && modified >= 3.5) {
            findings.add(persistAndPromote(newFinding(AnomalyDetectorType.FLOW_TURBULENCE, componentId, type,
                    null, null, null, List.of(), Map.of("turbulence", current), baseline.mean(), baseline.median(),
                    baseline.populationStddev(), baseline.mad(), z, modified, baseline.sampleCount(),
                    baseline.windowStart(), baseline.windowEnd(), boundary.minusSeconds(60), boundary, mode), mode));
        }
    }

    private void refreshBaselines(Instant boundary) {
        String scope = scopeId();
        clickHouse.flushLocationTransitMetrics();
        Instant fork = simulationId() != null
                ? Objects.requireNonNullElseGet(observations.getForkTimestamp(),
                        () -> simulations.getState(simulationId())
                                .map(LiveSimulationRepository.SimulationMetadata::timestamp)
                                .orElse(boundary))
                : boundary;
        List<ClickHouseService.TransitSample> samples = new ArrayList<>();
        if (simulationId() != null) {
            samples.addAll(clickHouse.getTransitSamples("live", boundary.minus(BASELINE_WINDOW),
                    fork.isBefore(boundary) ? fork : boundary));
            if (fork.isBefore(boundary)) {
                samples.addAll(clickHouse.getTransitSamples(simulationId(), fork, boundary));
            }
        } else {
            samples.addAll(clickHouse.getTransitSamples("live", boundary.minus(BASELINE_WINDOW), boundary));
        }

        Map<String, DetectorBaseline> replacement = new LinkedHashMap<>();
        samples.stream().collect(Collectors.groupingBy(sample -> transitKey(sample.conveyorId(),
                sample.sourceLocationId(), sample.targetLocationId()))).forEach((key, group) -> {
                    String conveyorId = group.getFirst().conveyorId();
                    Instant epoch = observations.getTimingEpoch(conveyorId);
                    List<Double> values = group.stream()
                            .filter(sample -> !sample.timestamp().isBefore(epoch))
                            .map(ClickHouseService.TransitSample::durationMillis).toList();
                    DetectorBaseline baseline = baseline(scope, AnomalyDetectorType.TRANSIT_TIME_SLOW, conveyorId,
                            key, values, boundary.minus(BASELINE_WINDOW), boundary, boundary, epoch);
                    replacement.put(key, baseline);
                });

        for (Conveyor conveyor : topology.getAllConveyors()) {
            addTurbulenceBaseline(replacement, "CONVEYOR:" + conveyor.getId(), conveyor.getId(), boundary);
        }
        for (Location location : topology.getAllLocations()) {
            addTurbulenceBaseline(replacement, "LOCATION:" + location.getId(), location.getId(), boundary);
        }
        observations.replaceBaselines(replacement);
        replacement.values().forEach(clickHouse::saveDetectorBaseline);
    }

    private void addTurbulenceBaseline(Map<String, DetectorBaseline> replacement, String componentKey,
            String componentId, Instant boundary) {
        List<Long> throughput = observations.latestThroughput(componentKey, boundary.minusSeconds(1), 7 * 24 * 60);
        if (throughput.size() < 10) {
            return;
        }
        List<Double> windows = new ArrayList<>();
        for (int index = 0; index + 10 <= throughput.size(); index++) {
            List<Long> window = throughput.subList(index, index + 10);
            windows.add(standardDeviation(window.stream().map(Number::doubleValue).toList(), meanNumbers(window)));
        }
        String key = "turbulence:" + componentKey;
        replacement.put(key, baseline(scopeId(), AnomalyDetectorType.FLOW_TURBULENCE, componentId, key, windows,
                boundary.minus(BASELINE_WINDOW), boundary, boundary, Instant.EPOCH));
    }

    private DetectorBaseline baseline(String scope, AnomalyDetectorType detector, String componentId, String key,
            List<Double> samples, Instant from, Instant to, Instant calculatedAt, Instant epoch) {
        List<Double> sorted = samples.stream().sorted().toList();
        double mean = sorted.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double median = percentile50(sorted);
        List<Double> deviations = sorted.stream().map(value -> Math.abs(value - median)).sorted().toList();
        return new DetectorBaseline(deterministicId(scope, detector.name(), key, to.toString()), scope, VERSION,
                detector, componentId, key, from, to, sorted.size(), mean, standardDeviation(sorted, mean), median,
                percentile50(deviations), sorted.isEmpty() ? 0 : sorted.getFirst(),
                sorted.isEmpty() ? 0 : sorted.getLast(), calculatedAt, epoch);
    }

    private boolean isUsableBaseline(DetectorBaseline baseline, Instant observationTimestamp) {
        return baseline != null
                && baseline.sampleCount() >= MINIMUM_BASELINE_SAMPLES
                && baseline.windowEnd().isBefore(observationTimestamp)
                && !baseline.calculatedAt().plus(MAX_BASELINE_AGE).isBefore(observationTimestamp)
                && !observationTimestamp.isBefore(baseline.epochStartedAt());
    }

    private AnomalyFinding persistAndPromote(AnomalyFinding finding, AnomalyProcessingMode mode) {
        return persistAndPromote(finding, mode, true);
    }

    private AnomalyFinding persistAndPromote(AnomalyFinding finding, AnomalyProcessingMode mode,
            boolean announceFinding) {
        AnomalyFinding current = finding;
        saveFinding(current, mode, announceFinding);
        if (mode == AnomalyProcessingMode.LIVE || mode == AnomalyProcessingMode.FUTURE_SIMULATION) {
            try {
                ComponentAlarmRaisedEvent alarm = new ComponentAlarmRaisedEvent(current.alarmId(), current.findingId(),
                        current.componentId(), current.componentType(), current.detector().name(), AlarmSource.AUTOMATIC,
                        AlarmSeverity.WARNING, false, current.tickTimestamp());
                Map<String, Object> result = eventProcessor.process(alarm, true).join();
                AlarmPromotionState state = "IGNORED_DUPLICATE".equals(result.get("status"))
                        ? AlarmPromotionState.ALREADY_ACTIVE
                        : AlarmPromotionState.ACTIVE;
                current = withAlarmState(current, state);
                saveFinding(current, mode, false);
                if (state == AlarmPromotionState.ACTIVE) {
                    webSockets.broadcastAnomaly(simulationId(), new AnomalyNotification("ALARM_RAISED", current, null,
                            current.alarmId(), current.componentId(), current.tickTimestamp()));
                }
            } catch (RuntimeException error) {
                current = withAlarmState(current, AlarmPromotionState.PENDING);
                saveFinding(current, mode, false);
            }
        }
        return current;
    }

    private void saveFinding(AnomalyFinding finding, AnomalyProcessingMode mode, boolean announce) {
        observations.saveFinding(finding);
        clickHouse.saveAnomalyFinding(finding);
        if (announce && mode != AnomalyProcessingMode.HISTORICAL_BUILD) {
            webSockets.broadcastAnomaly(simulationId(), new AnomalyNotification("FINDING_DETECTED", finding, null,
                    finding.alarmId(), finding.componentId(), finding.tickTimestamp()));
        }
    }

    private void retryPendingPromotions(Instant boundary, AnomalyProcessingMode mode) {
        if (mode != AnomalyProcessingMode.LIVE && mode != AnomalyProcessingMode.FUTURE_SIMULATION) {
            return;
        }
        observations.getFindings(boundary.minus(Duration.ofMinutes(15)), boundary).stream()
                .filter(finding -> finding.alarmState() == AlarmPromotionState.PENDING)
                .forEach(finding -> persistAndPromote(finding, mode, false));
    }

    private void correlatePressureFindings(List<AnomalyFinding> newFindings, Instant boundary,
            AnomalyProcessingMode mode) {
        for (AnomalyFinding pressure : newFindings) {
            if (pressure.detector() != AnomalyDetectorType.SYSTEM_PRESSURE) {
                continue;
            }
            List<AnomalyFinding> candidates = observations.getFindings(boundary.minus(Duration.ofMinutes(15)), boundary)
                    .stream()
                    .filter(finding -> finding.detector() != AnomalyDetectorType.UNSCORABLE_CAPACITY)
                    .filter(finding -> graphDistance(pressure.componentId(), finding.componentId(), 10) >= 0)
                    .sorted(Comparator.comparing(AnomalyFinding::observationTimestamp)
                            .thenComparing(AnomalyFinding::severity, Comparator.reverseOrder())
                            .thenComparingInt(finding -> graphDistance(pressure.componentId(), finding.componentId(), 10)))
                    .toList();
            if (candidates.isEmpty()) {
                continue;
            }
            LinkedHashSet<String> components = candidates.stream().map(AnomalyFinding::componentId)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            String rootComponent = candidates.getFirst().componentId();
            boolean consistentlyOrdered = isConsistentlyOrdered(rootComponent, candidates);
            String confidence = components.size() >= 3 && consistentlyOrdered
                    ? "HIGH"
                    : components.size() >= 2 ? "MEDIUM" : "LOW";
            AnomalyIncident incident = new AnomalyIncident(
                    deterministicId(scopeId(), "incident", pressure.componentId(), pressure.tickTimestamp().toString()),
                    scopeId(), simulationId(), rootComponent, confidence,
                    candidates.stream().map(AnomalyFinding::findingId).toList(), List.copyOf(components),
                    candidates.getFirst().observationTimestamp(), boundary);
            observations.saveIncident(incident);
            clickHouse.saveAnomalyIncident(incident);
            if (mode != AnomalyProcessingMode.HISTORICAL_BUILD) {
                webSockets.broadcastAnomaly(simulationId(), new AnomalyNotification("INCIDENT_UPDATED", null, incident,
                        null, incident.probableRootComponentId(), boundary));
            }
        }
    }

    private boolean isConsistentlyOrdered(String rootComponent, List<AnomalyFinding> findings) {
        Set<String> seen = new HashSet<>();
        int previousDistance = -1;
        for (AnomalyFinding finding : findings) {
            if (!seen.add(finding.componentId())) {
                continue;
            }
            int distance = graphDistance(rootComponent, finding.componentId(), 10);
            if (distance < previousDistance) {
                return false;
            }
            previousDistance = distance;
        }
        return true;
    }

    private AnomalyFinding newFinding(AnomalyDetectorType detector, String componentId, ComponentType componentType,
            String itemId, String previousPositionId, String reportedPositionId, List<String> expected,
            Map<String, Object> metrics, Double mean, Double median, Double stddev, Double mad, Double z,
            Double modifiedZ, long samples, Instant baselineFrom, Instant baselineTo, Instant observedAt,
            Instant tickAt, AnomalyProcessingMode mode) {
        String findingId = deterministicId(scopeId(), detector.name(), componentId,
                Objects.toString(itemId, ""), observedAt.toString(), tickAt.toString());
        String alarmId = deterministicId(scopeId(), detector.name(), componentId);
        AlarmPromotionState state = mode == AnomalyProcessingMode.LIVE
                || mode == AnomalyProcessingMode.FUTURE_SIMULATION
                        ? AlarmPromotionState.PENDING
                        : AlarmPromotionState.NOT_APPLICABLE;
        return new AnomalyFinding(findingId, scopeId(), simulationId(), detector, VERSION, mode, componentId,
                componentType, itemId, previousPositionId, reportedPositionId, expected, metrics, mean, median, stddev,
                mad, z, modifiedZ, samples, baselineFrom, baselineTo, AlarmSeverity.WARNING, alarmId, state, observedAt,
                tickAt);
    }

    private AnomalyFinding withAlarmState(AnomalyFinding finding, AlarmPromotionState state) {
        return new AnomalyFinding(finding.findingId(), finding.scopeId(), finding.simulationId(), finding.detector(),
                finding.detectorVersion(), finding.temporalMode(), finding.componentId(), finding.componentType(),
                finding.itemId(), finding.previousPositionId(), finding.reportedPositionId(),
                finding.expectedIntermediatePositions(), finding.observedMetrics(), finding.baselineMean(),
                finding.baselineMedian(), finding.baselineStddev(), finding.baselineMad(), finding.zScore(),
                finding.modifiedZScore(), finding.sampleCount(), finding.baselineWindowStart(),
                finding.baselineWindowEnd(), finding.severity(), finding.alarmId(), state,
                finding.observationTimestamp(), finding.tickTimestamp());
    }

    private List<String> shortestPositionRoute(String start, String target, int maxTransitions) {
        if (start == null || target == null) {
            return List.of();
        }
        Deque<List<String>> queue = new ArrayDeque<>();
        queue.add(List.of(start));
        Set<String> seen = new HashSet<>(Set.of(start));
        while (!queue.isEmpty()) {
            List<String> path = queue.removeFirst();
            if (path.size() - 1 >= maxTransitions) {
                continue;
            }
            for (String neighbor : positionNeighbors(path.getLast())) {
                List<String> next = new ArrayList<>(path);
                next.add(neighbor);
                if (neighbor.equals(target)) {
                    return next;
                }
                if (seen.add(neighbor)) {
                    queue.addLast(next);
                }
            }
        }
        return List.of();
    }

    private Set<String> positionNeighbors(String positionId) {
        Set<String> result = new LinkedHashSet<>();
        topology.getAllConveyors().forEach(conveyor -> {
            if (conveyor.getSourceLocationId().equals(positionId)) {
                result.add(conveyor.getId());
            }
            if (conveyor.getId().equals(positionId)) {
                result.add(conveyor.getTargetLocationId());
                topology.getOutgoingConveyors(conveyor.getTargetLocationId()).stream()
                        .map(Conveyor::getId).forEach(result::add);
            }
        });
        return result;
    }

    private int graphDistance(String start, String target, int maxHops) {
        if (Objects.equals(start, target)) {
            return 0;
        }
        Map<String, Set<String>> graph = new HashMap<>();
        topology.getAllConveyors().forEach(conveyor -> {
            graph.computeIfAbsent(conveyor.getSourceLocationId(), ignored -> new HashSet<>()).add(conveyor.getId());
            graph.computeIfAbsent(conveyor.getId(), ignored -> new HashSet<>()).add(conveyor.getSourceLocationId());
            graph.computeIfAbsent(conveyor.getTargetLocationId(), ignored -> new HashSet<>()).add(conveyor.getId());
            graph.computeIfAbsent(conveyor.getId(), ignored -> new HashSet<>()).add(conveyor.getTargetLocationId());
        });
        Set<String> seen = new HashSet<>(Set.of(start));
        Set<String> frontier = Set.of(start);
        for (int distance = 1; distance <= maxHops; distance++) {
            Set<String> next = new HashSet<>();
            for (String current : frontier) {
                for (String neighbor : graph.getOrDefault(current, Set.of())) {
                    if (neighbor.equals(target)) {
                        return distance;
                    }
                    if (seen.add(neighbor)) {
                        next.add(neighbor);
                    }
                }
            }
            frontier = next;
        }
        return -1;
    }

    private ComponentType positionComponentType(PositionType type) {
        return type == PositionType.CONVEYOR ? ComponentType.CONVEYOR : ComponentType.LOCATION;
    }

    private String scopeId() {
        return simulationId() != null ? simulationId() : "live";
    }

    private String simulationId() {
        return DatabaseContextHolder.getSimulationId();
    }

    private String transitKey(String conveyor, String source, String target) {
        return "transit:" + conveyor + ":" + source + ":" + target;
    }

    private static double meanNumbers(List<? extends Number> values) {
        return values.stream().mapToDouble(Number::doubleValue).average().orElse(0.0);
    }

    private static double standardDeviation(List<Double> values, double mean) {
        if (values.isEmpty()) {
            return 0.0;
        }
        return Math.sqrt(values.stream().mapToDouble(value -> Math.pow(value - mean, 2)).sum() / values.size());
    }

    private static double percentile50(List<Double> sorted) {
        if (sorted.isEmpty()) {
            return 0.0;
        }
        int middle = sorted.size() / 2;
        return sorted.size() % 2 == 0 ? (sorted.get(middle - 1) + sorted.get(middle)) / 2.0 : sorted.get(middle);
    }

    private static String deterministicId(String... fields) {
        return UUID.nameUUIDFromBytes(String.join("|", fields).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static long positive(long value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
