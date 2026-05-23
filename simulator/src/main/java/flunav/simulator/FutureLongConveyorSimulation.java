package flunav.simulator;

import flunav.events.ItemCreatedEvent;
import flunav.events.ItemDeletedEvent;
import flunav.types.LocationType;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static flunav.simulator.SimulatorUtils.createConveyor;
import static flunav.simulator.SimulatorUtils.createLocation;
import static flunav.simulator.SimulatorUtils.deleteConveyor;
import static flunav.simulator.SimulatorUtils.deleteLocation;
import static flunav.simulator.SimulatorUtils.itemCounter;
import static flunav.simulator.SimulatorUtils.logger;
import static flunav.simulator.SimulatorUtils.sendEvent;

class FutureLongConveyorSimulation implements Simulation {
    private static final String ENTRY = "FutureEntry";
    private static final String BUFFER = "FutureBuffer";
    private static final String MID = "FutureMid";
    private static final String EXIT = "FutureExit";
    private static final int CLEANUP_ITEM_COUNT = 5000;
    private static final double CONVEYOR_LENGTH = 600.0;
    private static final double CONVEYOR_SPEED = 1.0;
    private static final int EXPECTED_ROUTE_MINUTES = 30;
    private static final Random random = new Random();

    @Override
    public void setup() throws Exception {
        logger.info("--- Setting up Future Long Conveyor Simulation ---");

        createLocation(ENTRY, 0.0, 0.0, LocationType.JUNCTION);
        createLocation(BUFFER, 0.0, 100.0, LocationType.JUNCTION);
        createLocation(MID, 0.0, 200.0, LocationType.JUNCTION);
        createLocation(EXIT, 0.0, 300.0, LocationType.CHUTE);

        logger.info("Waiting for nodes to persist...");
        Thread.sleep(1000);

        logger.info("--- Creating long future-playback conveyors ---");
        createConveyor(ENTRY, BUFFER, CONVEYOR_LENGTH, CONVEYOR_SPEED, true);
        createConveyor(BUFFER, MID, CONVEYOR_LENGTH, CONVEYOR_SPEED, true);
        createConveyor(MID, EXIT, CONVEYOR_LENGTH, CONVEYOR_SPEED, true);
    }

    @Override
    public void destroy() throws Exception {
        logger.info("--- Destroying Future Long Conveyor Simulation ---");

        deleteConveyor(ENTRY, BUFFER);
        deleteConveyor(BUFFER, MID);
        deleteConveyor(MID, EXIT);

        deleteLocation(ENTRY);
        deleteLocation(BUFFER);
        deleteLocation(MID);
        deleteLocation(EXIT);

        logger.info("Cleaning up potential FutureBox items...");
        for (int i = 0; i < CLEANUP_ITEM_COUNT; i++) {
            try {
                sendEvent(new ItemDeletedEvent("FutureBox-" + i), "DELETE");
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void run() throws Exception {
        logger.info("--- Starting slow FutureBox injection at FutureEntry ---");

        while (true) {
            long delay = 30_000L + random.nextInt(30_001);
            Thread.sleep(delay);

            String itemId = "FutureBox-" + itemCounter.incrementAndGet();
            logger.info("Injecting " + itemId + " into 30 minute route at " + ENTRY);

            try {
                sendEvent(new ItemDeletedEvent(itemId), "DELETE");
            } catch (Exception ignored) {
            }

            Map<String, Object> attributes = new HashMap<>();
            attributes.put("barcode", "F" + String.format("%011d", itemCounter.get()));
            attributes.put("priority", random.nextInt(10) == 0 ? "HIGH" : "NORMAL");
            attributes.put("expectedRouteMinutes", EXPECTED_ROUTE_MINUTES);

            sendEvent(new ItemCreatedEvent(itemId, itemId, 1.0, true, ENTRY,
                    flunav.types.PositionType.LOCATION, 0.0, attributes), "POST");
        }
    }
}
