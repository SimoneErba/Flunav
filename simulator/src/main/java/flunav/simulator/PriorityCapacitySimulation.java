package flunav.simulator;

import flunav.events.ChuteEmptyEvent;
import flunav.events.ItemCreatedEvent;
import flunav.events.ItemDeletedEvent;
import flunav.types.LocationType;
import flunav.types.PositionType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static flunav.simulator.SimulatorUtils.createConveyor;
import static flunav.simulator.SimulatorUtils.createLocation;
import static flunav.simulator.SimulatorUtils.deleteConveyor;
import static flunav.simulator.SimulatorUtils.deleteLocation;
import static flunav.simulator.SimulatorUtils.logger;
import static flunav.simulator.SimulatorUtils.sendEvent;

class PriorityCapacitySimulation implements Simulation {
    private static final String ENTRY = "PC-ENTRY";
    private static final String DECISION = "PC-DECISION";
    private static final String LOOP_0 = "PC-LOOP-0";
    private static final String LOOP_1 = "PC-LOOP-1";
    private static final String LOOP_2 = "PC-LOOP-2";
    private static final String RESERVED_CHUTE = "PC-RESERVED-CHUTE";
    private static final String OPEN_CHUTE = "PC-OPEN-CHUTE";
    private static final String ITEM_PREFIX = "PC-Item-";
    private static final int RESERVED_CHUTE_CAPACITY = 10;
    private static final int OPEN_CHUTE_CAPACITY = 4;

    private final List<String> activeTrafficItems = new ArrayList<>();
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
    }

    @Override
    public void run() throws Exception {
        requireRabbitMode();
        logger.info("--- Starting priority reserved-capacity demo cycle ---");
        while (true) {
            runCycle();
            Thread.sleep(6000);
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

    private void runCycle() throws Exception {
        cycle++;
        logger.info("Priority capacity cycle " + cycle
                + ": normal items should recirculate at 9/10, high priority should use the reserved slot.");

        resetChutes();
        deleteActiveTrafficItems();
        prefillReservedChute(9);
        Thread.sleep(1000);

        createTrafficItem("normal-reserved-a", RESERVED_CHUTE, "NORMAL");
        Thread.sleep(1200);
        createTrafficItem("normal-reserved-b", RESERVED_CHUTE, "NORMAL");
        Thread.sleep(1200);
        createTrafficItem("normal-reserved-c", RESERVED_CHUTE, "NORMAL");
        Thread.sleep(7000);

        createTrafficItem("high-reserved-slot", RESERVED_CHUTE, "HIGH");
        Thread.sleep(1500);
        createTrafficItem("normal-open-chute", OPEN_CHUTE, "NORMAL");
        Thread.sleep(7000);

        logger.info("Resetting reserved chute to 8/10 so a normal-priority item can exit.");
        deleteActiveTrafficItems();
        resetChutes();
        prefillReservedChute(8);
        Thread.sleep(1000);

        createTrafficItem("normal-reserved-below-threshold", RESERVED_CHUTE, "NORMAL");
        Thread.sleep(7000);
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
            createItem("reserved-fill-" + index, RESERVED_CHUTE, null, "FILLER");
            Thread.sleep(100);
        }
    }

    private void createTrafficItem(String label, String destination, String priority) throws Exception {
        String itemId = createItem(label, ENTRY, destination, priority);
        activeTrafficItems.add(itemId);
        logger.info("Injected " + itemId + " priority=" + priority + " destination=" + destination);
    }

    private String createItem(String label, String locationId, String destination, String priority) throws Exception {
        String itemId = ITEM_PREFIX + (++itemSequence);
        sendEvent(new ItemDeletedEvent(itemId), "DELETE");

        Map<String, Object> properties = new HashMap<>();
        properties.put("scenario", "priority-capacity");
        properties.put("label", label);
        properties.put("priority", priority);
        properties.put("cycle", cycle);

        List<String> destinations = destination == null ? null : List.of(destination);
        sendEvent(new ItemCreatedEvent(
                itemId,
                label,
                1.0,
                true,
                locationId,
                PositionType.LOCATION,
                0.0,
                destinations,
                properties), "POST");
        return itemId;
    }

    private void deleteActiveTrafficItems() throws Exception {
        for (String itemId : activeTrafficItems) {
            sendEvent(new ItemDeletedEvent(itemId), "DELETE");
        }
        activeTrafficItems.clear();
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
