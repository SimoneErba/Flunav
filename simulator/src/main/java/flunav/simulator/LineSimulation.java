package flunav.simulator;

import flunav.events.ItemCreatedEvent;
import flunav.events.ItemDeletedEvent;
import flunav.types.LocationType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Random;

import static flunav.simulator.SimulatorUtils.createConveyor;
import static flunav.simulator.SimulatorUtils.createLocation;
import static flunav.simulator.SimulatorUtils.deleteConveyor;
import static flunav.simulator.SimulatorUtils.deleteLocation;
import static flunav.simulator.SimulatorUtils.itemCounter;
import static flunav.simulator.SimulatorUtils.logger;
import static flunav.simulator.SimulatorUtils.sendEvent;

class LineSimulation implements Simulation {
    private static final int NUM_LOCATIONS = 10;
    private static final Random random = new Random();
    private final List<String> locations = new ArrayList<>();

    @Override
    public void setup() throws Exception {
        logger.info("--- Setting up a line of " + NUM_LOCATIONS + " locations ---");
        for (int i = 0; i < NUM_LOCATIONS; i++) {
            String locationName = "LineLoc-" + i;
            createLocation(locationName, 0.0, i * 15.0,
                    i == NUM_LOCATIONS ? LocationType.CHUTE : LocationType.JUNCTION);
            locations.add(locationName);
        }

        logger.info("Waiting for nodes to persist...");
        Thread.sleep(1000);

        logger.info("--- Creating connections ---");
        for (int i = 0; i < NUM_LOCATIONS - 1; i++) {
            createConveyor(locations.get(i), locations.get(i + 1), 10.0, 2.0, true);
        }
    }

    @Override
    public void destroy() throws Exception {
        logger.info("--- Destroying Line Simulation ---");
        for (int i = 0; i < NUM_LOCATIONS - 1; i++) {
            deleteConveyor(locations.get(i), locations.get(i + 1));
        }
        for (int i = 0; i < NUM_LOCATIONS; i++) {
            deleteLocation("LineLoc-" + i);
        }
        logger.info("Cleaning up potential items...");
        for (int i = 0; i < 2000; i++) {
            try {
                sendEvent(new ItemDeletedEvent("Item-" + i), "DELETE");
            } catch (Exception e) {
            }
        }
    }

    @Override
    public void run() throws Exception {
        logger.info("--- Starting periodic item injection at the start of the line ---");
        String entryPoint = locations.get(0);

        while (true) {
            long delay = 1000 + random.nextInt(4000);
            Thread.sleep(delay);

            String itemId = "Item-" + itemCounter.incrementAndGet();
            logger.info("Injecting new item '" + itemId + "' at entry point '" + entryPoint + "'");

            try {
                sendEvent(new ItemDeletedEvent(itemId), "DELETE");
            } catch (Exception ignored) {
            }

            sendEvent(new ItemCreatedEvent(itemId, itemId, 1.0, true, entryPoint,
                    flunav.types.PositionType.LOCATION, 0.0, new HashMap<>()), "POST");
        }
    }
}
