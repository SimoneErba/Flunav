package flunav.simulator;

import flunav.events.ConnectionDeactivatedEvent;
import flunav.events.ConnectionPropertiesUpdatedEvent;
import flunav.events.ItemCreatedEvent;
import flunav.events.ItemDestinationEvent;
import flunav.events.ItemPositionChangedEvent;
import flunav.types.LocationType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static flunav.simulator.SimulatorUtils.createConveyor;
import static flunav.simulator.SimulatorUtils.createLocation;
import static flunav.simulator.SimulatorUtils.deleteConveyor;
import static flunav.simulator.SimulatorUtils.deleteLocation;
import static flunav.simulator.SimulatorUtils.itemCounter;
import static flunav.simulator.SimulatorUtils.logger;
import static flunav.simulator.SimulatorUtils.sendEvent;

class LargeLoopSimulation implements Simulation {
    private static final int MAIN_LOOP_NODES = 100;
    private static final int NUM_ENTRANCES = 4;
    private static final int NUM_EXITS = 10;
    private static final double RADIUS = 200.0;
    private static final Random random = new Random();

    private final List<String> brokenConveyors = new ArrayList<>();
    private final List<String> allConveyorIds = new ArrayList<>();
    private final List<String> allLocationIds = new ArrayList<>();

    @Override
    public void setup() throws Exception {
        logger.info("--- Setting up LARGE SCALE Facility ---");

        for (int i = 0; i < MAIN_LOOP_NODES; i++) {
            String id = "MainLoop-" + i;
            double angle = 2 * Math.PI * i / MAIN_LOOP_NODES;
            double lat = RADIUS * Math.sin(angle);
            double lon = RADIUS * Math.cos(angle);
            createLocation(id, lat, lon, LocationType.JUNCTION);
            allLocationIds.add(id);
        }

        for (int i = 0; i < NUM_ENTRANCES; i++) {
            String id = "Spawn-" + i;
            int targetIndex = (MAIN_LOOP_NODES / NUM_ENTRANCES) * i;
            double angle = 2 * Math.PI * targetIndex / MAIN_LOOP_NODES;
            double lat = (RADIUS + 40) * Math.sin(angle);
            double lon = (RADIUS + 40) * Math.cos(angle);

            createLocation(id, lat, lon, LocationType.JUNCTION);
            allLocationIds.add(id);
        }

        for (int i = 0; i < NUM_EXITS; i++) {
            String id = "Chute-" + i;
            int targetIndex = (MAIN_LOOP_NODES / NUM_EXITS) * i + 5;
            double angle = 2 * Math.PI * targetIndex / MAIN_LOOP_NODES;
            double lat = (RADIUS - 40) * Math.sin(angle);
            double lon = (RADIUS - 40) * Math.cos(angle);

            createLocation(id, lat, lon, LocationType.CHUTE);
            allLocationIds.add(id);
        }

        logger.info("Waiting for nodes to persist...");
        Thread.sleep(2000);

        for (int i = 0; i < MAIN_LOOP_NODES; i++) {
            String from = "MainLoop-" + i;
            String to = "MainLoop-" + ((i + 1) % MAIN_LOOP_NODES);
            String edgeId = "Conveyor_" + from + "_" + to;
            createConveyor(from, to, 12.0, 2.0, true);
            allConveyorIds.add(edgeId);
        }

        for (int i = 0; i < NUM_ENTRANCES; i++) {
            String from = "Spawn-" + i;
            int targetIndex = (MAIN_LOOP_NODES / NUM_ENTRANCES) * i;
            String to = "MainLoop-" + targetIndex;
            String edgeId = "Conveyor_" + from + "_" + to;
            createConveyor(from, to, 15.0, 1.5, false);
            allConveyorIds.add(edgeId);
        }

        for (int i = 0; i < NUM_EXITS; i++) {
            int sourceIndex = (MAIN_LOOP_NODES / NUM_EXITS) * i + 5;
            String from = "MainLoop-" + sourceIndex;
            String to = "Chute-" + i;
            String edgeId = "Conveyor_" + from + "_" + to;
            createConveyor(from, to, 10.0, 1.5, false);
            allConveyorIds.add(edgeId);
        }

        createConveyor("MainLoop-10", "MainLoop-60", 250.0, 4.0, false);
        allConveyorIds.add("Conveyor_MainLoop-10_MainLoop-60");

        createConveyor("MainLoop-40", "MainLoop-90", 250.0, 4.0, false);
        allConveyorIds.add("Conveyor_MainLoop-40_MainLoop-90");
    }

    @Override
    public void destroy() throws Exception {
        logger.info("--- Destroying Large Simulation ---");
        for (String id : allConveyorIds) {
            String[] parts = id.split("_");
            if (parts.length >= 3) {
                deleteConveyor(parts[1], parts[2]);
            }
        }
        for (String id : allLocationIds) {
            deleteLocation(id);
        }

        logger.info("Cleaning up items...");
    }

    @Override
    public void run() throws Exception {
        logger.info("--- Starting Large Scale Simulation Loop ---");

        while (true) {
            Thread.sleep(500 + random.nextInt(1000));

            String itemId = "BoxLarge-" + itemCounter.incrementAndGet();
            String spawnPoint = "Spawn-" + random.nextInt(NUM_ENTRANCES);
            String destination = "Chute-" + random.nextInt(NUM_EXITS);

            Map<String, Object> attributes = new HashMap<>();
            attributes.put("weight", 0.5 + (random.nextDouble() * 20.0));
            attributes.put("length", 20 + random.nextInt(60));
            attributes.put("barcode", "L" + String.format("%09d", random.nextInt(1000000000)));
            double priority = random.nextBoolean() ? 1.0 : 0.0;

            sendEvent(new ItemCreatedEvent(itemId, itemId, 1.5, priority, true, spawnPoint,
                    flunav.types.PositionType.LOCATION, 0.0, attributes), "POST");

            new Thread(() -> {
                try {
                    Thread.sleep(200);
                    sendEvent(new ItemDestinationEvent(itemId, destination), "PUT");
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }).start();

            if (random.nextInt(100) < 0 && !allConveyorIds.isEmpty()) {
                String targetEdge = allConveyorIds.get(random.nextInt(allConveyorIds.size()));
                if (!brokenConveyors.contains(targetEdge)) {
                    logger.warning("!!! BREAKDOWN SIMULATED on " + targetEdge + " !!!");

                    sendEvent(new ConnectionDeactivatedEvent(targetEdge), "PUT");

                    Map<String, Object> props = new HashMap<>();
                    props.put("error_message", "BELT_FAILURE_ERR_0" + random.nextInt(9));
                    props.put("status", "ERROR");
                    sendEvent(new ConnectionPropertiesUpdatedEvent(targetEdge, props), "PUT");

                    brokenConveyors.add(targetEdge);
                }
            }

            if (random.nextInt(100) < 1) {
                long lostIdNum = Math.max(1, itemCounter.get() - random.nextInt(50));
                String lostItemId = "BoxLarge-" + lostIdNum;
                String randomCheckpoint = "MainLoop-" + random.nextInt(MAIN_LOOP_NODES);

                logger.info("??? ITEM RE-ACQUIRED at checkpoint: " + lostItemId + " at " + randomCheckpoint);

                sendEvent(new ItemPositionChangedEvent(lostItemId, randomCheckpoint, 0.0), "PUT");
            }
        }
    }
}
