package flunav.simulator;

import flunav.events.ChuteEmptyEvent;
import flunav.events.DestinationExitMappingRecord;
import flunav.events.DestinationMappingRecord;
import flunav.events.ItemCreatedEvent;
import flunav.events.ItemDeletedEvent;
import flunav.events.MapDestinationExitsEvent;
import flunav.events.MapDestinationsEvent;
import flunav.events.MapDisplayRulesEvent;
import flunav.types.DataType;
import flunav.types.DisplayRule;
import flunav.types.LocationType;
import flunav.types.OperatorType;
import flunav.types.PositionType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static flunav.simulator.SimulatorUtils.createConveyor;
import static flunav.simulator.SimulatorUtils.createLocation;
import static flunav.simulator.SimulatorUtils.deleteConveyor;
import static flunav.simulator.SimulatorUtils.deleteLocation;
import static flunav.simulator.SimulatorUtils.logger;
import static flunav.simulator.SimulatorUtils.sendEvent;
import static flunav.simulator.SimulatorUtils.sendRawHttp;
import static flunav.simulator.SimulatorUtils.toJson;

class PriorityCapacitySimulation implements Simulation {
    private static final String ENTRY = "PC-ENTRY";
    private static final String DECISION = "PC-DECISION";
    private static final String LOOP_0 = "PC-LOOP-0";
    private static final String LOOP_1 = "PC-LOOP-1";
    private static final String LOOP_2 = "PC-LOOP-2";
    private static final String RESERVED_CHUTE = "PC-RESERVED-CHUTE";
    private static final String OPEN_CHUTE = "PC-OPEN-CHUTE";
    private static final String LOGICAL_DESTINATION = "PC-DESTINATION";
    private static final String ITEM_PREFIX = "PC-Item-";
    private static final int RESERVED_CHUTE_CAPACITY = 10;
    private static final int OPEN_CHUTE_CAPACITY = 4;

    private long itemSequence;
    private long cycle;

    @Override
    public void setup() throws Exception {
        requireRabbitMode();
        logger.info("--- Setting up Priority Capacity Simulation ---");

        createLocation(ENTRY, 0, 0, LocationType.JUNCTION, 0);
        createLocation(DECISION, 0, 12, LocationType.DECISION_POINT, 0);
        createLocation(LOOP_0, -14, 18, LocationType.JUNCTION, 0);
        createLocation(LOOP_1, -14, 2, LocationType.JUNCTION, 0);
        createLocation(LOOP_2, -6, -6, LocationType.JUNCTION, 0);
        createLocation(RESERVED_CHUTE, 16, 18, LocationType.CHUTE, RESERVED_CHUTE_CAPACITY);
        createLocation(OPEN_CHUTE, 16, 6, LocationType.CHUTE, OPEN_CHUTE_CAPACITY);

        logger.info("Waiting for priority-capacity nodes to persist...");
        Thread.sleep(1500);

        createConveyor(ENTRY, DECISION, 4.0, 2.0, true);
        createConveyor(DECISION, RESERVED_CHUTE, 4.0, 2.0, false);
        createConveyor(DECISION, OPEN_CHUTE, 4.0, 2.0, false);
        createConveyor(DECISION, LOOP_0, 4.0, 2.0, true);
        createConveyor(LOOP_0, LOOP_1, 3.0, 2.0, true);
        createConveyor(LOOP_1, LOOP_2, 3.0, 2.0, true);
        createConveyor(LOOP_2, DECISION, 3.0, 2.0, true);

        // 1. Send Display Rules for Flight colors
        logger.info("Sending flight-based display rules...");
        List<DisplayRule> rules = List.of(
            new DisplayRule("flight", DataType.NUMBER, OperatorType.EQUAL, 1, "#3b82f6", 1), // Blue for Flight 1
            new DisplayRule("flight", DataType.NUMBER, OperatorType.EQUAL, 2, "#22c55e", 1)  // Green for Flight 2
        );
        sendEvent(new MapDisplayRulesEvent(rules), "PUT");

        // 2. Send Destination Mappings (Routing logic based on flight)
        logger.info("Sending flight-based destination mappings...");
        sendEvent(new MapDestinationsEvent("flight", List.of(
            new DestinationMappingRecord("flight", DataType.NUMBER, OperatorType.EQUAL, "1", List.of("FLIGHT-1-CHUTE"), null, null),
            new DestinationMappingRecord("flight", DataType.NUMBER, OperatorType.EQUAL, "2", List.of("FLIGHT-2-CHUTE"), null, null)
        )), "PUT");

        // 3. Map Logical Destinations to Physical Exits
        logger.info("Mapping logical flight chutes to physical exits...");
        sendEvent(new MapDestinationExitsEvent(List.of(
                new DestinationExitMappingRecord("FLIGHT-1-CHUTE", List.of(RESERVED_CHUTE)),
                new DestinationExitMappingRecord("FLIGHT-2-CHUTE", List.of(OPEN_CHUTE)),
                new DestinationExitMappingRecord(LOGICAL_DESTINATION, List.of(RESERVED_CHUTE, OPEN_CHUTE)))), "PUT");
    }

    @Override
    public void run() throws Exception {
        requireRabbitMode();
        logger.info("--- Starting continuous priority reserved-capacity demo flow ---");
        resetChutes();
        prefillReservedChute(9);
        while (true) {
            injectFlowItem();
            drainOpenChuteIfNeeded();
            refreshReservedCapacityIfNeeded();
            Thread.sleep(800);
        }
    }

    @Override
    public void destroy() throws Exception {
        requireRabbitMode();
        logger.info("--- Destroying Priority Capacity Simulation ---");

        deleteConveyor(LOOP_2, DECISION);
        deleteConveyor(LOOP_1, LOOP_2);
        deleteConveyor(LOOP_0, LOOP_1);
        deleteConveyor(DECISION, LOOP_0);
        deleteConveyor(DECISION, OPEN_CHUTE);
        deleteConveyor(DECISION, RESERVED_CHUTE);
        deleteConveyor(ENTRY, DECISION);

        deleteLocation(OPEN_CHUTE);
        deleteLocation(RESERVED_CHUTE);
        deleteLocation(LOOP_2);
        deleteLocation(LOOP_1);
        deleteLocation(LOOP_0);
        deleteLocation(DECISION);
        deleteLocation(ENTRY);

        cleanupItems();
    }

    private void injectFlowItem() throws Exception {
        cycle++;
        int flight = (cycle % 2 == 0) ? 1 : 2;
        boolean highPriority = cycle % 7 == 0;
        double priority = highPriority ? 1.0 : 0.0;
        String label = "Flight-" + flight + "-" + (highPriority ? "HIGH" : "NORMAL");
        createTrafficItem(label, priority, flight);
    }

    private void resetChutes() throws Exception {
        logger.info("Clearing " + RESERVED_CHUTE + " and " + OPEN_CHUTE);
        sendEvent(new ChuteEmptyEvent(RESERVED_CHUTE), "PUT");
        sendEvent(new ChuteEmptyEvent(OPEN_CHUTE), "PUT");
        Thread.sleep(1000);
    }

    private void prefillReservedChute(int count) throws Exception {
        logger.info("Prefilling " + RESERVED_CHUTE + " to " + count + "/" + RESERVED_CHUTE_CAPACITY);
        for (int index = 0; index < count; index++) {
            createItem("reserved-fill-" + index, RESERVED_CHUTE, List.of(RESERVED_CHUTE), 0.0, 0);
            Thread.sleep(100);
        }
    }

    private void drainOpenChuteIfNeeded() throws Exception {
        if (cycle % 9 == 0) {
            logger.info("Draining " + OPEN_CHUTE + " to keep normal-priority flow moving.");
            sendEvent(new ChuteEmptyEvent(OPEN_CHUTE), "PUT");
        }
    }

    private void refreshReservedCapacityIfNeeded() throws Exception {
        if (cycle % 24 == 0) {
            logger.info("Refreshing " + RESERVED_CHUTE + " back to reserved capacity 9/" + RESERVED_CHUTE_CAPACITY);
            sendEvent(new ChuteEmptyEvent(RESERVED_CHUTE), "PUT");
            Thread.sleep(500);
            prefillReservedChute(9);
        }
    }

    private void createTrafficItem(String label, double priority, int flight) throws Exception {
        // Destination is now based on flight (mapped logically)
        String logicalDestination = (flight == 1) ? "FLIGHT-1-CHUTE" : "FLIGHT-2-CHUTE";
        List<String> destinations = List.of(logicalDestination);
        
        String itemId = createItem(label, ENTRY, destinations, priority, flight);
        logger.info("Injected " + itemId + " flight=" + flight + " priority=" + priority + " destinations=" + destinations);
    }

    private String createItem(String label, String locationId, List<String> destinations, double priority, int flight)
            throws Exception {
        String itemId = ITEM_PREFIX + (++itemSequence);
        sendEvent(new ItemDeletedEvent(itemId), "DELETE");

        Map<String, Object> properties = new HashMap<>();
        properties.put("scenario", "priority-capacity");
        properties.put("label", label);
        properties.put("flight", flight);
        properties.put("cycle", cycle);

        sendEvent(new ItemCreatedEvent(
                itemId,
                label,
                1.0,
                priority,
                true,
                locationId,
                PositionType.LOCATION,
                0.0,
                destinations,
                properties), "POST");
        return itemId;
    }

    private void cleanupItems() throws Exception {
        int cleanupCount = Integer.parseInt(System.getenv().getOrDefault("SIMULATOR_CLEANUP_ITEMS", "10000"));
        logger.info("Deleting up to " + cleanupCount + " priority-capacity demo items.");
        for (int index = cleanupCount; index >= 1; index--) {
            sendEvent(new ItemDeletedEvent(ITEM_PREFIX + index), "DELETE");
        }
        resetChutes();
    }

    private void requireRabbitMode() {
        if (!SimulatorUtils.MODE.equalsIgnoreCase("rabbit")) {
            throw new IllegalStateException(
                    "Priority capacity simulation requires SIMULATION_MODE=rabbit; current mode is "
                            + SimulatorUtils.MODE);
        }
    }
}
