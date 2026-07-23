package flunav.simulator;

import flunav.events.ChuteEmptyEvent;
import flunav.events.AlarmClearedEvent;
import flunav.events.AlarmRaisedEvent;
import flunav.events.ConnectionPropertiesUpdatedEvent;
import flunav.events.ItemCreatedEvent;
import flunav.events.ItemDeletedEvent;
import flunav.events.ItemPathChangedEvent;
import flunav.events.ItemPositionChangedEvent;
import flunav.types.PositionType;
import flunav.types.AlarmSeverity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

abstract class RoutingScenarioSimulation implements Simulation {
    private static final int FAILURE_WARMUP_SECONDS = 30;
    private static final int FAILURE_INTERVAL_SECONDS = 60;
    private static final int FAILURE_DURATION_SECONDS = 20;
    private static final int CHUTE_CLEAR_INTERVAL_SECONDS = 15;

    private final ScenarioTopology topology;
    private final Random propertyRandom;
    private long tick;
    private long itemSequence;
    private int failureIndex;
    private long nextFailureTick = FAILURE_WARMUP_SECONDS;
    private long failureStartedTick = -1;
    private ScenarioTopology.FailureSpec activeFailure;

    protected RoutingScenarioSimulation(ScenarioTopology topology) {
        this.topology = topology;
        this.propertyRandom = new Random(topology.name().hashCode());
        topology.validate();
    }

    @Override
    public final void setup() throws Exception {
        requireRabbitMode();
        SimulatorUtils.logger.info(() -> String.format(
                "--- Setting up %s: %d locations, %d conveyors ---",
                topology.name(),
                topology.locationCount(),
                topology.conveyorCount()));
        topology.create();
    }

    @Override
    public final void destroy() throws Exception {
        requireRabbitMode();
        SimulatorUtils.logger.info("--- Destroying " + topology.name() + " ---");
        topology.destroy();
        cleanupItems();
    }

    @Override
    public final void run() throws Exception {
        requireRabbitMode();
        SimulatorUtils.logger.info("--- Starting deterministic one-second routing ticks for " + topology.name()
                + " ---");
        while (true) {
            long startedAt = System.nanoTime();
            processFailureLifecycle();
            injectTickTraffic();
            clearChuteIfDue();
            tick++;

            long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;
            Thread.sleep(Math.max(0, 1000 - elapsedMillis));
        }
    }

    private void injectTickTraffic() throws Exception {
        int routeCycle = Math.toIntExact(tick % 4);
        switch (routeCycle) {
            case 0 -> injectAutomaticDestination();
            case 1 -> injectExplicitAlternateRoute();
            case 2 -> injectReacquiredItem();
            case 3 -> injectMainPathItem();
            default -> throw new IllegalStateException("Unexpected route cycle: " + routeCycle);
        }

        if (activeFailure != null && tick % 5 == 0) {
            injectOutageTraffic(activeFailure);
        }
    }

    private void injectAutomaticDestination() throws Exception {
        int index = Math.toIntExact(tick / 4);
        String entry = rotating(topology.entries(), index);
        String exit = rotating(topology.exits(), index * 3);
        createItem(entry, exit, "automatic");
    }

    private void injectExplicitAlternateRoute() throws Exception {
        int index = Math.toIntExact(tick / 4);
        List<String> path = rotating(topology.alternateRoutes(), index);
        String itemId = createItem(path.get(0), null, "explicit-alternate");
        SimulatorUtils.sendEvent(new ItemPathChangedEvent(itemId, path), "PUT");
    }

    private void injectReacquiredItem() throws Exception {
        int index = Math.toIntExact(tick / 4);
        List<String> path = rotating(topology.reacquisitionRoutes(), index);
        String entry = rotating(topology.entries(), index + 1);
        String itemId = createItem(entry, path.get(path.size() - 1), "reacquired");
        SimulatorUtils.sendEvent(new ItemPositionChangedEvent(itemId, path.get(0), 0.0), "PUT");
        SimulatorUtils.sendEvent(new ItemPathChangedEvent(itemId, path), "PUT");
    }

    private void injectMainPathItem() throws Exception {
        int index = Math.toIntExact(tick / 4);
        createItem(rotating(topology.entries(), index + 2), null, "main-path-recirculation");
    }

    private void processFailureLifecycle() throws Exception {
        if (activeFailure != null && tick - failureStartedTick >= FAILURE_DURATION_SECONDS) {
            repairFailure(activeFailure);
            activeFailure = null;
        }
        if (activeFailure == null && tick >= nextFailureTick) {
            ScenarioTopology.FailureSpec failure = rotating(topology.failures(), failureIndex++);
            startFailure(failure);
            activeFailure = failure;
            failureStartedTick = tick;
            nextFailureTick += FAILURE_INTERVAL_SECONDS;
        }
    }

    private void startFailure(ScenarioTopology.FailureSpec failure) throws Exception {
        SimulatorUtils.logger.warning("Raising deterministic alarms on bypassable conveyor " + failure.conveyorId());
        SimulatorUtils.sendEvent(new AlarmRaisedEvent(
                alarmId(failure, "warning"), failure.conveyorId(), AlarmSeverity.WARNING,
                "BELT_DEGRADATION", false), "POST");
        SimulatorUtils.sendEvent(new AlarmRaisedEvent(
                alarmId(failure, "stopping"), failure.conveyorId(), AlarmSeverity.CRITICAL,
                failure.errorCode(), true), "POST");
        SimulatorUtils.sendEvent(new AlarmRaisedEvent(
                alarmId(failure, "overlap"), failure.conveyorId(), AlarmSeverity.CRITICAL,
                "SAFETY_INTERLOCK", true), "POST");
        SimulatorUtils.sendEvent(new ConnectionPropertiesUpdatedEvent(
                failure.conveyorId(),
                Map.of(
                        "status", "ERROR",
                        "errorCode", failure.errorCode(),
                        "failureStartedTick", tick)), "PUT");

        Thread.sleep(250);
        List<String> inFlightPath = failure.inFlightPath();
        createItem(inFlightPath.get(0), inFlightPath.get(inFlightPath.size() - 1), "in-flight-failure");
    }

    private void injectOutageTraffic(ScenarioTopology.FailureSpec failure) throws Exception {
        List<String> path = failure.bypassPath();
        String itemId = createItem(path.get(0), null, "outage-bypass");
        SimulatorUtils.sendEvent(new ItemPathChangedEvent(itemId, path), "PUT");
    }

    private void repairFailure(ScenarioTopology.FailureSpec failure) throws Exception {
        SimulatorUtils.logger.info("Clearing deterministic alarms on conveyor " + failure.conveyorId());
        SimulatorUtils.sendEvent(new AlarmClearedEvent(
                alarmId(failure, "stopping"), failure.conveyorId(), AlarmSeverity.CRITICAL,
                failure.errorCode(), true), "POST");
        SimulatorUtils.sendEvent(new AlarmClearedEvent(
                alarmId(failure, "overlap"), failure.conveyorId(), AlarmSeverity.CRITICAL,
                "SAFETY_INTERLOCK", true), "POST");
        SimulatorUtils.sendEvent(new AlarmClearedEvent(
                alarmId(failure, "warning"), failure.conveyorId(), AlarmSeverity.WARNING,
                "BELT_DEGRADATION", false), "POST");
        SimulatorUtils.sendEvent(new ConnectionPropertiesUpdatedEvent(
                failure.conveyorId(),
                Map.of(
                        "status", "HEALTHY",
                        "errorCode", "",
                        "repairedAtTick", tick)), "PUT");
    }

    private String alarmId(ScenarioTopology.FailureSpec failure, String phase) {
        return topology.name() + "-" + failure.conveyorId() + "-" + failureIndex + "-" + phase;
    }

    private void clearChuteIfDue() throws Exception {
        if (tick > 0 && tick % CHUTE_CLEAR_INTERVAL_SECONDS == 0) {
            int index = Math.toIntExact(tick / CHUTE_CLEAR_INTERVAL_SECONDS);
            String chuteId = rotating(topology.exits(), index);
            SimulatorUtils.logger.info("Clearing chute " + chuteId);
            SimulatorUtils.sendEvent(new ChuteEmptyEvent(chuteId), "PUT");
        }
    }

    private String createItem(String entry, String destination, String trafficClass) throws Exception {
        String itemId = topology.itemPrefix() + (++itemSequence);
        Map<String, Object> properties = new HashMap<>();
        properties.put("scenario", topology.name());
        properties.put("trafficClass", trafficClass);
        properties.put("weightKg", Math.round((4.0 + propertyRandom.nextDouble() * 28.0) * 100.0) / 100.0);
        double priority = propertyRandom.nextInt(5) == 0 ? 1.0 : 0.0;
        properties.put("barcode", topology.itemPrefix() + String.format("%08d", propertyRandom.nextInt(100_000_000)));

        SimulatorUtils.sendEvent(new ItemCreatedEvent(
                itemId,
                itemId,
                1.6,
                priority,
                true,
                entry,
                PositionType.LOCATION,
                0.0,
                destination == null ? null : java.util.List.of(destination),
                properties), "POST");
        return itemId;
    }

    private void cleanupItems() throws Exception {
        int cleanupCount = Integer.parseInt(System.getenv().getOrDefault("SIMULATOR_CLEANUP_ITEMS", "10000"));
        SimulatorUtils.logger.info(
                "Deleting up to " + cleanupCount + " items with prefix " + topology.itemPrefix());
        for (int index = cleanupCount; index >= 1; index--) {
            SimulatorUtils.sendEvent(new ItemDeletedEvent(topology.itemPrefix() + index), "DELETE");
        }
    }

    private void requireRabbitMode() {
        if (!SimulatorUtils.MODE.equalsIgnoreCase("rabbit")) {
            throw new IllegalStateException(
                    topology.name() + " requires SIMULATION_MODE=rabbit; current mode is " + SimulatorUtils.MODE);
        }
    }

    private static <T> T rotating(List<T> values, int index) {
        if (values.isEmpty()) {
            throw new IllegalStateException("Scenario rotation list cannot be empty");
        }
        return values.get(Math.floorMod(index, values.size()));
    }
}
