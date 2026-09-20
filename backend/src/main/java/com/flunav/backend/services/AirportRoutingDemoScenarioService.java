package com.flunav.backend.services;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.PreDestroy;

import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.flunav.backend.context.DatabaseContextHolder;
import com.flunav.backend.models.simulation.SimulationState;

import flunav.events.ConnectionCreatedEvent;
import flunav.events.ConnectionDeactivatedEvent;
import flunav.events.ItemCreatedEvent;
import flunav.events.LocationCreatedEvent;
import flunav.events.LocationDeletedEvent;
import flunav.types.ConveyorType;
import flunav.types.LocationType;
import flunav.types.PositionType;

/** Runs an isolated baggage-routing demonstration with failure-aware recirculation. */
@Service
public class AirportRoutingDemoScenarioService {
    private static final String PREFIX = "airport-demo-";
    private static final String INFEED = PREFIX + "infeed";
    private static final String DECISION = PREFIX + "sorter";
    private static final String PRIMARY_MIDPOINT = PREFIX + "gate-a-transfer";
    private static final String ALTERNATE_MIDPOINT = PREFIX + "gate-b-transfer";
    private static final String PRIMARY_EXIT = PREFIX + "gate-a";
    private static final String ALTERNATE_EXIT = PREFIX + "gate-b";
    private static final String LOOP_FAR = PREFIX + "recirculation-far";
    private static final String LOOP_RETURN = PREFIX + "recirculation-return";
    private static final String FEED_CONVEYOR = PREFIX + "induction";
    private static final String PRIMARY_FIRST_CONVEYOR = PREFIX + "gate-a-approach";
    private static final String PRIMARY_SECOND_CONVEYOR = PREFIX + "gate-a-exit";
    private static final String ALTERNATE_FIRST_CONVEYOR = PREFIX + "gate-b-approach";
    private static final String ALTERNATE_SECOND_CONVEYOR = PREFIX + "gate-b-exit";
    private static final String LOOP_OUT = PREFIX + "recirculation-out";
    private static final String LOOP_ACROSS = PREFIX + "recirculation-across";
    private static final String LOOP_BACK = PREFIX + "recirculation-back";
    private static final int ITEM_COUNT = 18;

    private final EventProcessor events;
    private final TopologyProvider topology;
    private final SimulationService simulations;
    private final TimeService timeService;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    private volatile DemoStatus active;

    public AirportRoutingDemoScenarioService(EventProcessor events, TopologyProvider topology,
            SimulationService simulations, TimeService timeService) {
        this.events = events;
        this.topology = topology;
        this.simulations = simulations;
        this.timeService = timeService;
    }

    /** Builds the isolated airport loop and starts its virtual clock immediately. */
    public synchronized DemoStatus start() {
        if (active != null && active.running() && simulationExists(active.simulationId())) {
            throw new IllegalStateException("An airport routing demo is already running");
        }

        Instant startedAt = timeService.physicalNow();
        SimulationState simulation = simulations.createWhatIf(null);
        String simulationId = simulation.getId();
        simulations.isolateFromLiveInput(simulationId);
        active = new DemoStatus(simulationId, startedAt, startedAt.plusSeconds(25), true);

        inDemoContext(simulationId, () -> {
            clearClonedTopology();
            createTopology();
        });
        simulations.startPlayback(simulationId, 1.0);

        executor.schedule(() -> inDemoContext(simulationId, this::stopPrimaryRoute), 10, TimeUnit.SECONDS);
        for (int index = 1; index <= ITEM_COUNT; index++) {
            int itemNumber = index;
            long delayMillis = 500L + ((index - 1L) * 1_000L);
            executor.schedule(() -> inDemoContext(simulationId, () -> publishBag(itemNumber)),
                    delayMillis, TimeUnit.MILLISECONDS);
        }
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
        createLocation(INFEED, "Baggage induction", -1.0, 0.0, LocationType.TIMED_NODE, 100, 2_000L);
        createLocation(DECISION, "Airport sorter", 0.0, 0.0, LocationType.DECISION_POINT, 100);
        createLocation(PRIMARY_MIDPOINT, "Gate A transfer", 0.55, -0.55, LocationType.JUNCTION, 100);
        createLocation(ALTERNATE_MIDPOINT, "Gate B transfer", 0.55, 0.55, LocationType.JUNCTION, 100);
        createLocation(PRIMARY_EXIT, "Gate A", 1.1, -0.55, LocationType.CHUTE, 100);
        createLocation(ALTERNATE_EXIT, "Gate B", 1.1, 0.55, LocationType.CHUTE, 100);
        createLocation(LOOP_FAR, "Recirculation 1", 0.55, 1.0, LocationType.JUNCTION, 100);
        createLocation(LOOP_RETURN, "Recirculation 2", -0.45, 1.0, LocationType.JUNCTION, 100);

        createConveyor(FEED_CONVEYOR, INFEED, DECISION, "Induction belt", 25.0, true);
        createConveyor(PRIMARY_FIRST_CONVEYOR, DECISION, PRIMARY_MIDPOINT, "Gate A approach", 35.0, false);
        createConveyor(PRIMARY_SECOND_CONVEYOR, PRIMARY_MIDPOINT, PRIMARY_EXIT, "Gate A exit belt", 35.0, false);
        createConveyor(ALTERNATE_FIRST_CONVEYOR, DECISION, ALTERNATE_MIDPOINT, "Gate B approach", 40.0, false);
        createConveyor(ALTERNATE_SECOND_CONVEYOR, ALTERNATE_MIDPOINT, ALTERNATE_EXIT, "Gate B exit belt", 40.0,
                false);
        createConveyor(LOOP_OUT, DECISION, LOOP_FAR, "Recirculation outbound", 30.0, true);
        createConveyor(LOOP_ACROSS, LOOP_FAR, LOOP_RETURN, "Recirculation transfer", 30.0, true);
        createConveyor(LOOP_BACK, LOOP_RETURN, DECISION, "Recirculation return", 30.0, true);
    }

    private void createLocation(String id, String name, double x, double y, LocationType type, int capacity) {
        createLocation(id, name, x, y, type, capacity, null);
    }

    private void createLocation(String id, String name, double x, double y, LocationType type, int capacity,
            Long processingTimeMillis) {
        events.process(new LocationCreatedEvent(id, name, true, x, y, type, capacity,
                Map.of("scenario", "airport-routing-demo"), processingTimeMillis), true).join();
    }

    private void createConveyor(String id, String source, String target, String name, double length,
            boolean mainPath) {
        long traversalMillis = Math.round((length / 10.0) * 1_000.0);
        events.process(new ConnectionCreatedEvent(id, source, target, length, 10.0, 0.0, traversalMillis,
                mainPath, name, true, ConveyorType.BELT, 100,
                Map.of("scenario", "airport-routing-demo")), true).join();
    }

    /** Stops the preferred exit through the normal domain event so routing caches and clients update. */
    private void stopPrimaryRoute() {
        events.process(new ConnectionDeactivatedEvent(PRIMARY_SECOND_CONVEYOR), true).join();
    }

    /** Every fourth bag is Gate-A-only and demonstrates the loop after that route stops. */
    private void publishBag(int index) {
        List<String> destinations = index % 4 == 0
                ? List.of(PRIMARY_EXIT)
                : List.of(PRIMARY_EXIT, ALTERNATE_EXIT);
        Instant now = timeService.physicalNow();
        events.process(new ItemCreatedEvent(PREFIX + "bag-" + index, "Bag " + index, 18.0, 0.0,
                true, INFEED, PositionType.LOCATION, 0.0, destinations,
                Map.of("scenario", "airport-routing-demo", "flight", index % 4 == 0 ? "Gate A" : "Flexible"),
                now), true).join();
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
        if (current == null || !current.running() || !simulationId.equals(current.simulationId())) return;
        if (!simulationExists(simulationId)) {
            synchronized (this) {
                if (active != null && simulationId.equals(active.simulationId())) {
                    active = new DemoStatus(active.simulationId(), active.startedAt(), active.endsAt(), false);
                }
            }
            return;
        }

        Instant now = timeService.physicalNow();
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
