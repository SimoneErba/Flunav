package com.flunav.backend.services;

import com.flunav.backend.models.RedisLiveItem;
import com.flunav.backend.models.analytics.ConnectionStateSignal;
import com.flunav.backend.models.analytics.ExitCandidate;
import com.flunav.backend.models.response.ConveyorStopMetric;
import com.flunav.backend.models.response.JourneySummary;
import com.flunav.backend.repositories.LiveSimulationRepository;
import com.flunav.backend.context.DatabaseContextHolder;

import flunav.events.DomainEvent;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Service
public class OperationalAnalyticsService {
    private static final String LIVE_SCOPE = "live";

    private final ClickHouseService clickHouseService;
    private final LiveSimulationRepository liveSimulationRepository;
    private final TimeService timeService;

    public OperationalAnalyticsService(
            ClickHouseService clickHouseService,
            LiveSimulationRepository liveSimulationRepository,
            TimeService timeService) {
        this.clickHouseService = clickHouseService;
        this.liveSimulationRepository = liveSimulationRepository;
        this.timeService = timeService;
    }

    /**
     * Captures an exit before Redis removes the chute occupant. Historical replay
     * before a simulation restore point is excluded because that part of the
     * summary is supplied by immutable live facts.
     */
    public void recordSuccessfulExit(DomainEvent event, String chuteId, RedisLiveItem itemState, String itemId) {
        AnalyticsScope scope = currentScope(event.getTimestamp());
        if (!scope.record()) {
            return;
        }
        Instant simulationCreatedAt = scope.simulationId() != null
                && itemState != null
                && itemState.getCreatedAt() != null
                && itemState.getCreatedAt().isAfter(scope.restoreTimestamp())
                        ? itemState.getCreatedAt()
                        : null;
        String candidateId = event.getEventId() + ":" + itemId;
        clickHouseService.saveExitCandidateAsync(new ExitCandidate(
                candidateId,
                event.getEventId(),
                itemId,
                chuteId,
                event.getTimestamp(),
                scope.storageId(),
                simulationCreatedAt,
                scope.simulationId() == null ? event.getTimestamp() : scope.restoreTimestamp()));
    }

    /**
     * Flumen defines recirculation operationally as a changed reassignment after an
     * initial path exists; it does not require the item to traverse a physical loop.
     */
    public void recordRecirculation(String itemId, List<String> previousPath, List<String> newPath, Instant timestamp) {
        if (previousPath == null || newPath == null || Objects.equals(previousPath, newPath)) {
            return;
        }
        AnalyticsScope scope = currentScope(timestamp);
        if (!scope.record()) {
            return;
        }
        String identity = scope.storageId() + "\n" + itemId + "\n" + timestamp + "\n"
                + String.join("\u001f", previousPath) + "\n" + String.join("\u001f", newPath);
        String factId = UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString();
        clickHouseService.saveRecirculationAsync(
                factId, timestamp, scope.storageId(), itemId, previousPath, newPath);
    }

    /**
     * Stores only post-restore simulation connection signals. Live stop history is
     * reconstructed directly from the immutable domain event stream.
     */
    public void recordSimulationConnectionSignal(
            DomainEvent event,
            String conveyorId,
            Boolean active,
            Double speed,
            String sourceId,
            String targetId) {
        AnalyticsScope scope = currentScope(event.getTimestamp());
        if (!scope.record() || scope.simulationId() == null) {
            return;
        }
        clickHouseService.saveSimulationConnectionSignalAsync(new ConnectionStateSignal(
                event.getEventId(), event.getTimestamp(), scope.simulationId(), Objects.toString(conveyorId, ""),
                event.getEventType(), active, speed, Objects.toString(sourceId, ""), Objects.toString(targetId, "")));
    }

    public Instant contextNow() {
        String simulationId = DatabaseContextHolder.getSimulationId();
        if (simulationId == null) {
            return timeService.physicalNow();
        }
        return liveSimulationRepository.getState(simulationId)
                .map(metadata -> metadata.lastProcessedTimestamp() != null
                        ? metadata.lastProcessedTimestamp()
                        : metadata.timestamp())
                .orElseGet(timeService::physicalNow);
    }

    public CompletableFuture<JourneySummary> getJourneySummary(Instant from, Instant to) {
        String simulationId = DatabaseContextHolder.getSimulationId();
        Instant restoreTimestamp = simulationId == null
                ? null
                : liveSimulationRepository.getState(simulationId)
                        .map(LiveSimulationRepository.SimulationMetadata::timestamp)
                        .orElse(to);
        return clickHouseService.getJourneySummary(from, to, simulationId, restoreTimestamp);
    }

    /**
     * Reduces active and speed signals together so one stop remains open until the
     * conveyor is both active and moving. Intervals are clipped to the requested
     * window and open stops extend through the query end.
     */
    public CompletableFuture<List<ConveyorStopMetric>> getConveyorStops(Instant from, Instant to) {
        String simulationId = DatabaseContextHolder.getSimulationId();
        return CompletableFuture.supplyAsync(() -> {
            List<ConnectionStateSignal> signals;
            if (simulationId == null) {
                signals = clickHouseService.getLiveConnectionSignals(to);
            } else {
                Instant restoreTimestamp = liveSimulationRepository.getState(simulationId)
                        .map(LiveSimulationRepository.SimulationMetadata::timestamp)
                        .orElse(to);
                Instant liveEnd = to.isBefore(restoreTimestamp) ? to : restoreTimestamp;
                signals = new ArrayList<>(clickHouseService.getLiveConnectionSignals(liveEnd));
                if (to.isAfter(restoreTimestamp)) {
                    signals.addAll(clickHouseService.getSimulationConnectionSignals(simulationId, to));
                }
            }
            signals.sort(Comparator.comparing(ConnectionStateSignal::timestamp)
                    .thenComparing(ConnectionStateSignal::eventId));
            return calculateStops(signals, from, to);
        });
    }

    private List<ConveyorStopMetric> calculateStops(
            List<ConnectionStateSignal> signals,
            Instant from,
            Instant to) {
        Map<String, ConveyorState> states = new LinkedHashMap<>();
        Map<String, String> endpointIds = new HashMap<>();
        for (ConnectionStateSignal signal : signals) {
            String conveyorId = signal.conveyorId();
            if ("CONNECTION_DELETED".equals(signal.eventType())) {
                conveyorId = endpointIds.get(endpointKey(signal.sourceId(), signal.targetId()));
                if (conveyorId == null) {
                    continue;
                }
                ConveyorState state = states.get(conveyorId);
                if (state != null) {
                    state.closeStop(signal.timestamp());
                    state.exists = false;
                }
                endpointIds.remove(endpointKey(signal.sourceId(), signal.targetId()));
                continue;
            }
            if (conveyorId == null || conveyorId.isBlank()) {
                continue;
            }

            String resolvedConveyorId = conveyorId;
            ConveyorState state = states.get(resolvedConveyorId);
            if (state == null) {
                state = new ConveyorState(resolvedConveyorId);
                states.put(resolvedConveyorId, state);
            }
            if ("CONNECTION_CREATED".equals(signal.eventType())) {
                state.active = signal.active() == null || signal.active();
                state.speed = signal.speed() == null ? 1.0 : signal.speed();
                state.exists = true;
                endpointIds.put(endpointKey(signal.sourceId(), signal.targetId()), conveyorId);
            } else if (signal.active() != null) {
                state.active = signal.active();
            } else if (signal.speed() != null) {
                state.speed = signal.speed();
            }
            state.applyStoppedState(signal.timestamp());
        }

        long windowMillis = Math.max(0, Duration.between(from, to).toMillis());
        List<ConveyorStopMetric> metrics = new ArrayList<>();
        for (ConveyorState state : states.values()) {
            List<StopInterval> intervals = new ArrayList<>(state.intervals);
            if (state.stopStartedAt != null) {
                intervals.add(new StopInterval(state.stopStartedAt, to));
            }
            List<Long> overlaps = intervals.stream()
                    .map(interval -> overlapMillis(interval, from, to))
                    .filter(duration -> duration > 0)
                    .toList();
            if (!state.exists && overlaps.isEmpty()) {
                continue;
            }
            long total = overlaps.stream().mapToLong(Long::longValue).sum();
            long maximum = overlaps.stream().mapToLong(Long::longValue).max().orElse(0);
            double average = overlaps.isEmpty() ? 0.0 : total / (double) overlaps.size();
            double availability = windowMillis == 0
                    ? 100.0
                    : Math.max(0.0, Math.min(100.0, (windowMillis - total) * 100.0 / windowMillis));
            metrics.add(new ConveyorStopMetric(
                    state.conveyorId,
                    overlaps.size(),
                    total,
                    average,
                    maximum,
                    availability,
                    state.exists && state.stopStartedAt != null,
                    state.exists ? state.stopStartedAt : null));
        }
        metrics.sort(Comparator.comparingLong(ConveyorStopMetric::totalStoppedMillis).reversed()
                .thenComparing(ConveyorStopMetric::conveyorId));
        return metrics;
    }

    private long overlapMillis(StopInterval interval, Instant from, Instant to) {
        Instant start = interval.start().isAfter(from) ? interval.start() : from;
        Instant end = interval.end().isBefore(to) ? interval.end() : to;
        return end.isAfter(start) ? Duration.between(start, end).toMillis() : 0;
    }

    private String endpointKey(String sourceId, String targetId) {
        return Objects.toString(sourceId, "") + "\u0000" + Objects.toString(targetId, "");
    }

    private AnalyticsScope currentScope(Instant timestamp) {
        String simulationId = DatabaseContextHolder.getSimulationId();
        if (simulationId == null) {
            return new AnalyticsScope(null, null, true);
        }
        Instant restoreTimestamp = liveSimulationRepository.getState(simulationId)
                .map(LiveSimulationRepository.SimulationMetadata::timestamp)
                .orElse(null);
        return new AnalyticsScope(
                simulationId,
                restoreTimestamp,
                restoreTimestamp != null && !timestamp.isBefore(restoreTimestamp));
    }

    private record AnalyticsScope(String simulationId, Instant restoreTimestamp, boolean record) {
        private String storageId() {
            return simulationId == null ? LIVE_SCOPE : simulationId;
        }
    }

    private record StopInterval(Instant start, Instant end) {
    }

    private static final class ConveyorState {
        private final String conveyorId;
        private final List<StopInterval> intervals = new ArrayList<>();
        private boolean active = true;
        private double speed = 1.0;
        private boolean exists;
        private Instant stopStartedAt;

        private ConveyorState(String conveyorId) {
            this.conveyorId = conveyorId;
        }

        private void applyStoppedState(Instant timestamp) {
            boolean stopped = !active || speed <= 0.0;
            if (stopped && stopStartedAt == null) {
                stopStartedAt = timestamp;
            } else if (!stopped) {
                closeStop(timestamp);
            }
        }

        private void closeStop(Instant timestamp) {
            if (stopStartedAt == null) {
                return;
            }
            if (timestamp.isAfter(stopStartedAt)) {
                intervals.add(new StopInterval(stopStartedAt, timestamp));
            }
            stopStartedAt = null;
        }
    }
}
