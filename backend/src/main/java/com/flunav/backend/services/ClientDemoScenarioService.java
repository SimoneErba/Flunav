package com.flunav.backend.services;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.PreDestroy;

import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.flunav.backend.context.DatabaseContextHolder;
import com.flunav.backend.models.analytics.AnomalyProcessingMode;
import com.flunav.backend.models.analytics.AnomalyDetectorType;
import com.flunav.backend.models.analytics.DetectorBaseline;
import com.flunav.backend.models.simulation.SimulationState;
import com.flunav.backend.repositories.AnomalyObservationRepository;

import flunav.events.AnomalyEvaluationTickEvent;
import flunav.events.ConnectionCreatedEvent;
import flunav.events.ItemCreatedEvent;
import flunav.events.LocationCreatedEvent;
import flunav.events.LocationDeletedEvent;
import flunav.types.ConveyorType;
import flunav.types.LocationType;
import flunav.types.PositionType;

/** Runs a disposable, isolated one-minute flow-rate regression demonstration. */
@Service
public class ClientDemoScenarioService {
    private static final String PREFIX = "client-demo-";
    private static final String ENTRY = PREFIX + "infeed";
    private static final String EXIT = PREFIX + "outfeed";
    private static final String BELT = PREFIX + "main-belt";
    private static final int DURATION_SECONDS = 60;

    private final EventProcessor events;
    private final AnomalyObservationRepository observations;
    private final AnomalyEngine anomalyEngine;
    private final ClickHouseService clickHouse;
    private final TopologyProvider topology;
    private final SimulationService simulations;
    private final TimeService timeService;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    private volatile DemoStatus active;

    public ClientDemoScenarioService(EventProcessor events,
            AnomalyObservationRepository observations, AnomalyEngine anomalyEngine, ClickHouseService clickHouse,
            TopologyProvider topology, SimulationService simulations, TimeService timeService) {
        this.events = events;
        this.observations = observations;
        this.anomalyEngine = anomalyEngine;
        this.clickHouse = clickHouse;
        this.topology = topology;
        this.simulations = simulations;
        this.timeService = timeService;
    }

    /** Creates an isolated simulation and replaces only its cloned topology with the demo graph. */
    public synchronized DemoStatus start() {
        if (active != null && active.running() && simulationExists(active.simulationId())) {
            throw new IllegalStateException("A client demo is already running");
        }

        Instant startedAt = Instant.now();
        SimulationState simulation = simulations.createWhatIf(null);
        simulations.isolateFromLiveInput(simulation.getId());
        active = new DemoStatus(simulation.getId(), startedAt, startedAt.plusSeconds(DURATION_SECONDS), true);
        String simulationId = simulation.getId();
        inDemoContext(simulationId, () -> {
            clearClonedTopology();
            createTopology();
            seedHistoricalBaseline(startedAt);
        });
        executor.schedule(() -> inDemoContext(simulationId, () -> runReducedFlow(simulationId)),
                2, TimeUnit.SECONDS);
        executor.schedule(() -> inDemoContext(simulationId, this::evaluateFlow), 10, TimeUnit.SECONDS);
        return active;
    }

    public synchronized DemoStatus status() {
        if (active != null && active.running() && !simulationExists(active.simulationId())) {
            active = new DemoStatus(active.simulationId(), active.startedAt(), active.endsAt(), false);
        }
        return active;
    }

    public SimulationState simulationState() {
        DemoStatus current = active;
        return current != null ? simulations.getSimulationState(current.simulationId()) : null;
    }

    private void clearClonedTopology() {
        topology.getAllLocations().stream().map(location -> location.getId()).toList()
                .forEach(id -> events.process(new LocationDeletedEvent(id), true).join());
    }

    private void createTopology() {
        events.process(new LocationCreatedEvent(ENTRY, "Demo infeed", true, 0.0, 0.0, LocationType.JUNCTION, 100,
                Map.of("scenario", "client-demo")), true).join();
        events.process(new LocationCreatedEvent(EXIT, "Demo outfeed", true, 1.0, 0.0, LocationType.CHUTE, 100,
                Map.of("scenario", "client-demo")), true).join();
        events.process(new ConnectionCreatedEvent(BELT, ENTRY, EXIT, 100.0, 10.0, 0.0, 10000L, true, BELT, true,
                ConveyorType.BELT, 100, Map.of("scenario", "client-demo")), true).join();
    }

    /** Seeds ClickHouse and the existing turbulence detector with stable normal flow. */
    private void seedHistoricalBaseline(Instant now) {
        for (int minute = 40; minute >= 1; minute--) {
            Instant bucket = now.minus(minute, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MINUTES);
            observations.appendThroughput("CONVEYOR:" + BELT, bucket, 12);
            observations.appendThroughput("LOCATION:" + EXIT, bucket, 12);
            clickHouse.saveComponentFlowBucket(bucket, active.simulationId(), active.simulationId(), BELT,
                    "CONVEYOR", 12, 12);
            clickHouse.saveComponentFlowBucket(bucket, active.simulationId(), active.simulationId(), EXIT,
                    "LOCATION", 12, 12);
        }
        putTurbulenceBaseline(BELT, "CONVEYOR", now);
        putTurbulenceBaseline(EXIT, "LOCATION", now);
    }

    private void putTurbulenceBaseline(String componentId, String componentType, Instant now) {
        String key = "turbulence:" + componentType + ":" + componentId;
        DetectorBaseline baseline = new DetectorBaseline(PREFIX + "baseline-" + componentId,
                active.simulationId(), "v1",
                AnomalyDetectorType.FLOW_TURBULENCE, componentId, key, now.minus(40, ChronoUnit.MINUTES),
                now.minusMillis(1), 31, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, now, Instant.EPOCH);
        observations.putBaseline(key, baseline);
        clickHouse.saveDetectorBaseline(baseline);
    }

    /** Publishes six visible journeys, deliberately below the learned 12/minute baseline. */
    private void runReducedFlow(String simulationId) {
        long[] delays = { 1, 11, 21, 31, 41, 51 };
        for (int index = 1; index <= delays.length; index++) {
            final int itemNumber = index;
            executor.schedule(() -> inDemoContext(simulationId, () -> publishJourney(itemNumber)),
                    delays[index - 1], TimeUnit.SECONDS);
        }
    }

    private void publishJourney(int index) {
        String itemId = PREFIX + "item-" + index;
        Instant now = Instant.now();
        events.process(new ItemCreatedEvent(itemId, "Demo parcel " + index, 10.0, 0.0,
                true, ENTRY, PositionType.LOCATION, 0.0, List.of(EXIT), Map.of("scenario", "client-demo"), now),
                true).join();
    }

    /** Runs the existing flow-turbulence evaluation at the demo's ten-second checkpoint. */
    private synchronized void evaluateFlow() {
        Instant boundary = Instant.now();
        anomalyEngine.evaluate(new AnomalyEvaluationTickEvent(AnomalyEvaluationTickEvent.Cadence.MINUTE, boundary),
                AnomalyProcessingMode.FUTURE_SIMULATION);
    }

    private boolean simulationExists(String simulationId) {
        try {
            simulations.getSimulationState(simulationId);
            return true;
        } catch (ResponseStatusException missing) {
            if (missing.getStatusCode().value() == 404) return false;
            throw missing;
        }
    }

    private void inDemoContext(String simulationId, Runnable action) {
        DemoStatus current = active;
        if (current == null || !current.running() || !simulationId.equals(current.simulationId())) {
            return;
        }
        if (!simulationExists(simulationId)) {
            synchronized (this) {
                if (active != null && simulationId.equals(active.simulationId())) {
                    active = new DemoStatus(active.simulationId(), active.startedAt(), active.endsAt(), false);
                }
            }
            return;
        }
        Instant now = Instant.now();
        try (var database = DatabaseContextHolder.enterSimulationContext(simulationId);
                var virtualTime = timeService.enterVirtualTime(now)) {
            action.run();
            simulations.updateSimulationProgress(simulationId, now);
        }
    }

    @PreDestroy
    void stop() {
        executor.shutdownNow();
    }

    public record DemoStatus(String simulationId, Instant startedAt, Instant endsAt, boolean running) {
    }
}
