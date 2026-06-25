package flunav.simulator;

import flunav.events.ItemCreatedEvent;
import flunav.events.ItemDeletedEvent;
import flunav.events.ItemDestinationEvent;
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

class MultiPath implements Simulation {
    private static final Random random = new Random();

    @Override
    public void setup() throws Exception {
        logger.info("--- Setting up Sorting Hub Simulation ---");

        createLocation("Entry", 0, -20, LocationType.JUNCTION);
        createLocation("Hub", 0, 0, LocationType.JUNCTION);
        createLocation("Exit_A", 20, 20, LocationType.CHUTE);
        createLocation("Exit_B", 0, 20, LocationType.CHUTE);
        createLocation("Exit_Default", -20, 20, LocationType.CHUTE);

        logger.info("Waiting for nodes to persist...");
        Thread.sleep(1000);

        logger.info("--- Creating Connections ---");
        createConveyor("Entry", "Hub", 10.0, 2.0, false);
        createConveyor("Hub", "Exit_A", 20.0, 5.0, false);
        createConveyor("Hub", "Exit_B", 10.0, 0.5, false);
        createConveyor("Hub", "Exit_Default", 15.0, 1.0, true);
    }

    @Override
    public void destroy() throws Exception {
        logger.info("--- Destroying MultiPath Simulation ---");
        deleteConveyor("Entry", "Hub");
        deleteConveyor("Hub", "Exit_A");
        deleteConveyor("Hub", "Exit_B");
        deleteConveyor("Hub", "Exit_Default");
        deleteLocation("Entry");
        deleteLocation("Hub");
        deleteLocation("Exit_A");
        deleteLocation("Exit_B");
        deleteLocation("Exit_Default");

        logger.info("Cleaning up potential items...");
        for (int i = 0; i < 2000; i++) {
            try {
                sendEvent(new ItemDeletedEvent("BoxMulti-" + i), "DELETE");
            } catch (Exception e) {
            }
        }
    }

    @Override
    public void run() throws Exception {
        logger.info("--- Starting Sorting Logic ---");
        int cycle = 0;
        while (true) {
            Thread.sleep(10000);
            String itemId = "BoxMulti-" + itemCounter.incrementAndGet();
            String destination = null;
            int scenario = cycle % 3;

            if (scenario == 0) {
                destination = "Exit_A";
                logger.info("Injecting " + itemId + " -> Target: " + destination + " (Should take FAST lane)");
            } else if (scenario == 1) {
                destination = "Exit_B";
                logger.info("Injecting " + itemId + " -> Target: " + destination + " (Should take SLOW lane)");
            } else {
                destination = null;
                logger.info("Injecting " + itemId + " -> No Target (Should take MAIN/DEFAULT lane)");
            }

            try {
                sendEvent(new ItemDeletedEvent(itemId), "DELETE");
            } catch (Exception e) {
            }

            double weight = 1.0 + random.nextDouble() * 49.0;
            int height = 10 + random.nextInt(71);
            int width = 10 + random.nextInt(71);
            int depth = 10 + random.nextInt(71);
            long barcodeValue = random.nextLong(1_000_000_000_000L);
            String barcode = String.format("%012d", barcodeValue);

            Map<String, Object> attributes = new HashMap<>();
            attributes.put("weight", Double.parseDouble(String.format("%.2f", weight)));
            attributes.put("height", height);
            attributes.put("width", width);
            attributes.put("depth", depth);
            attributes.put("barcode", barcode);

            sendEvent(new ItemCreatedEvent(itemId, itemId, 1.0, 0.0, true, "Entry", flunav.types.PositionType.LOCATION,
                    0.0, attributes), "POST");

            if (destination != null) {
                Thread.sleep(100);
                sendEvent(new ItemDestinationEvent(itemId, destination), "PUT");
            }
            cycle++;
        }
    }
}
