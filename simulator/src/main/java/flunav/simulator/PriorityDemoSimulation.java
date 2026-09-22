package flunav.simulator;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import flunav.events.ConnectionActivatedEvent;
import flunav.events.ConnectionCreatedEvent;
import flunav.events.ConnectionDeactivatedEvent;
import flunav.events.DestinationExitMappingRecord;
import flunav.events.ItemCreatedEvent;
import flunav.events.ItemDeletedEvent;
import flunav.events.MapDestinationExitsEvent;
import flunav.events.MapDisplayRulesEvent;
import flunav.types.DataType;
import flunav.types.ConveyorType;
import flunav.types.DisplayRule;
import flunav.types.LocationType;
import flunav.types.OperatorType;
import flunav.types.PositionType;

import static flunav.simulator.SimulatorUtils.MODE;
import static flunav.simulator.SimulatorUtils.createLocation;
import static flunav.simulator.SimulatorUtils.deleteConveyor;
import static flunav.simulator.SimulatorUtils.deleteLocation;
import static flunav.simulator.SimulatorUtils.logger;
import static flunav.simulator.SimulatorUtils.sendEvent;

/** A bounded demonstration of priority-aware overflow during an exit failure. */
class PriorityDemoSimulation implements Simulation {
    private static final String ENTRY = "PD-ENTRY";
    private static final String DECISION = "PD-DECISION";
    private static final String LOOP_0 = "PD-LOOP-0";
    private static final String LOOP_1 = "PD-LOOP-1";
    private static final String LOOP_2 = "PD-LOOP-2";
    private static final String EXIT_1 = "PD-EXIT-1";
    private static final String EXIT_2 = "PD-EXIT-2";
    private static final String EXIT_3 = "PD-EXIT-3";
    private static final String SHARED_DESTINATION = "PD-BLUE-GREEN";
    private static final String ORANGE_DESTINATION = "PD-ORANGE";
    private static final String ITEM_PREFIX = "PD-Item-";
    private static final int DURATION_MS = 60_000;
    private static final int INJECTION_INTERVAL_MS = 1_000;
    private static final double CONVEYOR_SPEED = 0.25;
    private static final List<String> EXITS = List.of(EXIT_1, EXIT_2, EXIT_3);
    private static final List<String> LOCATIONS = List.of(
            ENTRY, DECISION, LOOP_0, LOOP_1, LOOP_2, EXIT_1, EXIT_2, EXIT_3);
    private static final List<List<String>> CONNECTIONS = List.of(
            List.of(ENTRY, DECISION), List.of(DECISION, EXIT_1),
            List.of(DECISION, EXIT_2), List.of(DECISION, EXIT_3),
            List.of(DECISION, LOOP_0), List.of(LOOP_0, LOOP_1),
            List.of(LOOP_1, LOOP_2), List.of(LOOP_2, DECISION));

    @Override
    public void setup() throws Exception {
        requireRabbitMode();
        createLocation(ENTRY, 0, 0, LocationType.JUNCTION, 0);
        createLocation(DECISION, 0, 12, LocationType.DECISION_POINT, 0);
        createLocation(LOOP_0, -14, 18, LocationType.JUNCTION, 0);
        createLocation(LOOP_1, -14, 2, LocationType.JUNCTION, 0);
        createLocation(LOOP_2, -6, -6, LocationType.JUNCTION, 0);
        createLocation(EXIT_1, 16, 24, LocationType.CHUTE, 10);
        createLocation(EXIT_2, 16, 12, LocationType.CHUTE, 10);
        createLocation(EXIT_3, 16, 0, LocationType.CHUTE, 10);
        Thread.sleep(1500);

        for (List<String> connection : CONNECTIONS) {
            boolean mainPath = !EXITS.contains(connection.get(1));
            sendEvent(new ConnectionCreatedEvent(
                    conveyorId(connection), connection.get(0), connection.get(1),
                    3.0, CONVEYOR_SPEED, 0.0, (long) (3.0 / CONVEYOR_SPEED * 1000),
                    mainPath, conveyorId(connection), true, ConveyorType.BELT, 60, Map.of()), "POST");
        }
        sendEvent(new MapDisplayRulesEvent(List.of(
                new DisplayRule("flight", DataType.NUMBER, OperatorType.EQUAL, 1, "#3b82f6", 1),
                new DisplayRule("flight", DataType.NUMBER, OperatorType.EQUAL, 2, "#22c55e", 1),
                new DisplayRule("flight", DataType.NUMBER, OperatorType.EQUAL, 3, "#f97316", 1))), "PUT");
        sendEvent(new MapDestinationExitsEvent(List.of(
                new DestinationExitMappingRecord(SHARED_DESTINATION, List.of(EXIT_1, EXIT_2)),
                new DestinationExitMappingRecord(ORANGE_DESTINATION, List.of(EXIT_3)))), "PUT");
        Thread.sleep(1500);
    }

    /** Uses elapsed-time deadlines so publishing time does not accumulate into timing drift. */
    @Override
    public void run() throws Exception {
        requireRabbitMode();
        clearItems();
        for (List<String> connection : CONNECTIONS) {
            sendEvent(new ConnectionActivatedEvent(conveyorId(connection)), "PUT");
        }
        Thread.sleep(1500);
        logger.info("[00s] Priority demo started: blue/green -> exits 1/2; orange -> exit 3.");
        long started = System.nanoTime();
        for (int tick = 0; tick <= DURATION_MS / INJECTION_INTERVAL_MS; tick++) {
            long deadline = started + TimeUnit.MILLISECONDS.toNanos((long) tick * INJECTION_INTERVAL_MS);
            long remaining = deadline - System.nanoTime();
            if (remaining > 0) {
                TimeUnit.NANOSECONDS.sleep(remaining);
            }
            if (System.nanoTime() - started >= TimeUnit.MILLISECONDS.toNanos(DURATION_MS)) {
                break;
            }
            int elapsedMs = tick * INJECTION_INTERVAL_MS;
            if (elapsedMs == 15_000) {
                sendEvent(new ConnectionDeactivatedEvent(conveyorId(CONNECTIONS.get(1))), "PUT");
                sendEvent(new ConnectionDeactivatedEvent(conveyorId(CONNECTIONS.get(3))), "PUT");
                logger.info("[15s] Exits 1 and 3 stopped. Blue/green divert to exit 2; orange and overflow recirculate.");
            } else if (elapsedMs == 25_000) {
                sendEvent(new ConnectionActivatedEvent(conveyorId(CONNECTIONS.get(1))), "PUT");
                sendEvent(new ConnectionActivatedEvent(conveyorId(CONNECTIONS.get(3))), "PUT");
                logger.info("[25s] Exits 1 and 3 repaired. Blue/green use both exits again; orange can leave via exit 3.");
            }
            injectItem(tick + 1);
        }
        logger.info("Priority demo injection stopped; its conveyors remain active.");
    }

    private void injectItem(int sequence) throws Exception {
        int flight = (sequence - 1) % 3 + 1;
        double priority = sequence % 4 == 0 ? 1.0 : 0.0;
        String label = "Flight-" + flight + (priority == 1.0 ? "-HIGH" : "-NORMAL");
        sendEvent(new ItemCreatedEvent(
                ITEM_PREFIX + sequence, label, 1.0, priority, true, ENTRY,
                PositionType.LOCATION, 0.0,
                List.of(flight == 3 ? ORANGE_DESTINATION : SHARED_DESTINATION),
                Map.of("scenario", "priority-demo", "flight", flight, "label", label)), "POST");
    }

    @Override
    public void destroy() throws Exception {
        requireRabbitMode();
        clearItems();
        for (int index = CONNECTIONS.size() - 1; index >= 0; index--) {
            List<String> connection = CONNECTIONS.get(index);
            deleteConveyor(connection.get(0), connection.get(1));
        }
        for (int index = LOCATIONS.size() - 1; index >= 0; index--) {
            deleteLocation(LOCATIONS.get(index));
        }
    }

    private void clearItems() throws Exception {
        for (int sequence = 1; sequence <= DURATION_MS / INJECTION_INTERVAL_MS; sequence++) {
            sendEvent(new ItemDeletedEvent(ITEM_PREFIX + sequence), "DELETE");
        }
    }

    private static String conveyorId(List<String> connection) {
        return "Conveyor_" + connection.get(0) + "_" + connection.get(1);
    }

    private static void requireRabbitMode() {
        if (!MODE.equalsIgnoreCase("rabbit")) {
            throw new IllegalStateException("Priority demo requires SIMULATION_MODE=rabbit");
        }
    }
}
