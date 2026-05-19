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

class ConveyorLoopSimulation implements Simulation {
    private static final int NUM_MAIN_LOCATIONS = 8;
    private static final int NUM_ENTRANCES = 2;
    private static final int NUM_EXITS = 2;
    private static final double LAYOUT_RADIUS = 100.0;
    private static final Random random = new Random();

    @Override
    public void setup() throws Exception {
        logger.info("--- Setting up a conveyor loop with entrances and exits ---");
        List<String> mainLoopLocations = new ArrayList<>();

        for (int i = 0; i < NUM_MAIN_LOCATIONS; i++) {
            String locName = "LoopLoc-" + i;
            double angle = 2 * Math.PI * i / NUM_MAIN_LOCATIONS;
            double lat = LAYOUT_RADIUS * Math.sin(angle);
            double lon = LAYOUT_RADIUS * Math.cos(angle);
            createLocation(locName, lat, lon, LocationType.JUNCTION);
            mainLoopLocations.add(locName);
        }

        for (int i = 0; i < NUM_ENTRANCES; i++) {
            createLocation("Entrance-" + i, 0.0, -150 - (i * 20.0), LocationType.JUNCTION);
        }

        for (int i = 0; i < NUM_EXITS; i++) {
            createLocation("Exit-" + i, 0.0, 150 + (i * 20.0), LocationType.CHUTE);
        }

        logger.info("Waiting for nodes to persist...");
        Thread.sleep(1000);

        logger.info("--- Creating connections for the loop ---");
        for (int i = 0; i < NUM_MAIN_LOCATIONS; i++) {
            createConveyor(mainLoopLocations.get(i), mainLoopLocations.get((i + 1) % NUM_MAIN_LOCATIONS), 20.0, 5.0,
                    true);
        }
        createConveyor("Entrance-0", "LoopLoc-0", 15.0, 2.0, false);
        createConveyor("Entrance-1", "LoopLoc-1", 15.0, 2.0, false);
        createConveyor("LoopLoc-4", "Exit-0", 10.0, 5.0, false);
        createConveyor("LoopLoc-5", "Exit-1", 10.0, 5.0, false);
    }

    @Override
    public void destroy() throws Exception {
        logger.info("--- Destroying Loop Simulation ---");
        for (int i = 0; i < NUM_MAIN_LOCATIONS; i++) {
            deleteConveyor("LoopLoc-" + i, "LoopLoc-" + ((i + 1) % NUM_MAIN_LOCATIONS));
        }
        deleteConveyor("Entrance-0", "LoopLoc-0");
        deleteConveyor("Entrance-1", "LoopLoc-1");
        deleteConveyor("LoopLoc-4", "Exit-0");
        deleteConveyor("LoopLoc-5", "Exit-1");

        for (int i = 0; i < NUM_MAIN_LOCATIONS; i++) {
            deleteLocation("LoopLoc-" + i);
        }
        for (int i = 0; i < NUM_ENTRANCES; i++) {
            deleteLocation("Entrance-" + i);
        }
        for (int i = 0; i < NUM_EXITS; i++) {
            deleteLocation("Exit-" + i);
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
        logger.info("--- Starting periodic item injection at random entrances ---");
        while (true) {
            long delay = 1000 + random.nextInt(4000);
            Thread.sleep(delay);

            String itemId = "Item-" + itemCounter.incrementAndGet();
            String entryPoint = "Entrance-" + random.nextInt(NUM_ENTRANCES);

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
