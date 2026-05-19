package flunav.simulator;

import flunav.events.ChuteEmptyEvent;
import flunav.events.ItemCreatedEvent;
import flunav.events.ItemDeletedEvent;
import flunav.events.ItemDestinationEvent;
import flunav.events.ItemPositionChangedEvent;
import flunav.types.LocationType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static flunav.simulator.SimulatorUtils.MODE;
import static flunav.simulator.SimulatorUtils.createConveyor;
import static flunav.simulator.SimulatorUtils.createLocation;
import static flunav.simulator.SimulatorUtils.deleteConveyor;
import static flunav.simulator.SimulatorUtils.deleteLocation;
import static flunav.simulator.SimulatorUtils.itemCounter;
import static flunav.simulator.SimulatorUtils.logger;
import static flunav.simulator.SimulatorUtils.sendEvent;

class PerformanceStressSimulation implements Simulation {
    private static final int MAIN_LOOP_NODES = 9000;
    private static final int NUM_ENTRANCES = 40;
    private static final int NUM_SORTERS = 480;
    private static final int NUM_CHUTES = 480;
    private static final int INITIAL_ITEM_COUNT = 1000;
    private static final int EXPRESS_LANES = 120;
    private static final int BATCH_SIZE = 250;
    private static final Random random = new Random();

    private final List<String> mainLoopIds = new ArrayList<>();
    private final List<String> entranceIds = new ArrayList<>();
    private final List<String> sorterIds = new ArrayList<>();
    private final List<String> chuteIds = new ArrayList<>();
    private final List<String> teleportTargets = new ArrayList<>();
    private final List<String> createdItemIds = new ArrayList<>();

    @Override
    public void setup() throws Exception {
        logger.info("--- Setting up PERFORMANCE STRESS facility with 10,000 locations ---");

        createMainLoop();
        createEntrances();
        createSortersAndChutes();

        logger.info("Waiting for locations to persist before creating conveyors...");
        Thread.sleep(4000);

        connectMainLoop();
        connectEntrances();
        connectSortingBranches();
        connectExpressLanes();

        logger.info(() -> "Performance topology ready. loopNodes=" + mainLoopIds.size()
                + ", entrances=" + entranceIds.size()
                + ", sorters=" + sorterIds.size()
                + ", chutes=" + chuteIds.size());
    }

    @Override
    public void run() throws Exception {
        logger.info("--- Starting PERFORMANCE STRESS traffic ---");
        if (!MODE.equalsIgnoreCase("rabbit")) {
            logger.warning(
                    "Performance mode is best run with SIMULATION_MODE=rabbit because destination and chute-clear flows are event-driven.");
        }
        logger.info("Spawning initial 1000 items with destinations...");
        spawnItems(INITIAL_ITEM_COUNT);

        long cycle = 0;
        while (true) {
            Thread.sleep(1000);
            cycle++;

            if (cycle % 2 == 0) {
                retargetItems(60);
            }

            if (cycle % 3 == 0) {
                teleportItems(24);
            }

            if (cycle % 5 == 0) {
                spawnItems(80);
            }

            if (MODE.equalsIgnoreCase("rabbit") && cycle % 6 == 0) {
                emptyRandomChutes(16);
            }

            if (cycle % 15 == 0) {
                logger.info("Performance cycle=" + cycle
                        + ", createdItems=" + createdItemIds.size()
                        + ", teleports=" + (cycle / 3) * 24);
            }
        }
    }

    @Override
    public void destroy() throws Exception {
        logger.info("--- Destroying PERFORMANCE STRESS facility ---");

        for (int i = 0; i < createdItemIds.size(); i++) {
            try {
                sendEvent(new ItemDeletedEvent(createdItemIds.get(i)), "DELETE");
            } catch (Exception e) {
                logger.fine("Ignoring item cleanup error for " + createdItemIds.get(i));
            }
        }

        for (int i = 0; i < EXPRESS_LANES; i++) {
            int fromIndex = (i * (MAIN_LOOP_NODES / EXPRESS_LANES)) % MAIN_LOOP_NODES;
            int skip = 35 + (i % 9) * 11;
            int toIndex = (fromIndex + skip) % MAIN_LOOP_NODES;
            deleteConveyor(mainLoopIds.get(fromIndex), mainLoopIds.get(toIndex));
        }

        for (int i = 0; i < NUM_SORTERS; i++) {
            int anchorIndex = (i * MAIN_LOOP_NODES) / NUM_SORTERS;
            int rejoinIndex = (anchorIndex + 9 + (i % 13)) % MAIN_LOOP_NODES;
            deleteConveyor(mainLoopIds.get(anchorIndex), sorterIds.get(i));
            deleteConveyor(sorterIds.get(i), chuteIds.get(i));
            deleteConveyor(sorterIds.get(i), mainLoopIds.get(rejoinIndex));
        }

        for (int i = 0; i < NUM_ENTRANCES; i++) {
            int attachIndex = (i * MAIN_LOOP_NODES) / NUM_ENTRANCES;
            deleteConveyor(entranceIds.get(i), mainLoopIds.get(attachIndex));
        }

        for (int i = 0; i < MAIN_LOOP_NODES; i++) {
            deleteConveyor(mainLoopIds.get(i), mainLoopIds.get((i + 1) % MAIN_LOOP_NODES));
        }

        for (String chuteId : chuteIds) {
            deleteLocation(chuteId);
        }
        for (String sorterId : sorterIds) {
            deleteLocation(sorterId);
        }
        for (String entranceId : entranceIds) {
            deleteLocation(entranceId);
        }
        for (String loopId : mainLoopIds) {
            deleteLocation(loopId);
        }
    }

    private void createMainLoop() throws Exception {
        for (int i = 0; i < MAIN_LOOP_NODES; i++) {
            String id = "PerfLoop-" + i;
            double angle = 2 * Math.PI * i / MAIN_LOOP_NODES;
            double radius = 420.0
                    + 45.0 * Math.sin(angle * 3.0)
                    + 25.0 * Math.cos(angle * 7.0);
            double lat = radius * Math.sin(angle);
            double lon = (radius * 1.35 + 35.0 * Math.sin(angle * 5.0)) * Math.cos(angle);
            createLocation(id, lat, lon, LocationType.JUNCTION);
            mainLoopIds.add(id);
            teleportTargets.add(id);

            if ((i + 1) % BATCH_SIZE == 0) {
                logger.info("Created main loop nodes: " + (i + 1) + "/" + MAIN_LOOP_NODES);
                Thread.sleep(100);
            }
        }
    }

    private void createEntrances() throws Exception {
        for (int i = 0; i < NUM_ENTRANCES; i++) {
            String id = "PerfEntrance-" + i;
            double angle = 2 * Math.PI * i / NUM_ENTRANCES;
            double lat = (560.0 + 25.0 * Math.sin(angle * 2.0)) * Math.sin(angle);
            double lon = (720.0 + 35.0 * Math.cos(angle * 3.0)) * Math.cos(angle);
            createLocation(id, lat, lon, LocationType.JUNCTION);
            entranceIds.add(id);
        }
    }

    private void createSortersAndChutes() throws Exception {
        for (int i = 0; i < NUM_SORTERS; i++) {
            int anchorIndex = (i * MAIN_LOOP_NODES) / NUM_SORTERS;
            double angle = 2 * Math.PI * anchorIndex / MAIN_LOOP_NODES;

            String sorterId = "PerfSorter-" + i;
            String chuteId = "PerfChute-" + i;

            double sorterLat = (500.0 + 40.0 * Math.sin(angle * 4.0)) * Math.sin(angle);
            double sorterLon = (570.0 + 30.0 * Math.cos(angle * 6.0)) * Math.cos(angle);
            double chuteLat = (640.0 + 45.0 * Math.cos(angle * 5.0)) * Math.sin(angle);
            double chuteLon = (760.0 + 55.0 * Math.sin(angle * 3.0)) * Math.cos(angle);

            createLocation(sorterId, sorterLat, sorterLon, LocationType.JUNCTION);
            createLocation(chuteId, chuteLat, chuteLon, LocationType.CHUTE, 250);

            sorterIds.add(sorterId);
            chuteIds.add(chuteId);
            teleportTargets.add(sorterId);

            if ((i + 1) % 120 == 0) {
                logger.info("Created sorting clusters: " + (i + 1) + "/" + NUM_SORTERS);
                Thread.sleep(150);
            }
        }
    }

    private void connectMainLoop() throws Exception {
        for (int i = 0; i < MAIN_LOOP_NODES; i++) {
            createConveyor(
                    mainLoopIds.get(i),
                    mainLoopIds.get((i + 1) % MAIN_LOOP_NODES),
                    6.0 + (i % 5),
                    2.4 + ((i % 7) * 0.15),
                    true,
                    false);

            if ((i + 1) % BATCH_SIZE == 0) {
                logger.info("Created main loop conveyors: " + (i + 1) + "/" + MAIN_LOOP_NODES);
                Thread.sleep(100);
            }
        }
    }

    private void connectEntrances() throws Exception {
        for (int i = 0; i < NUM_ENTRANCES; i++) {
            int attachIndex = (i * MAIN_LOOP_NODES) / NUM_ENTRANCES;
            createConveyor(
                    entranceIds.get(i),
                    mainLoopIds.get(attachIndex),
                    18.0 + (i % 4) * 3.0,
                    1.8 + (i % 5) * 0.25,
                    false,
                    false);
        }
    }

    private void connectSortingBranches() throws Exception {
        for (int i = 0; i < NUM_SORTERS; i++) {
            int anchorIndex = (i * MAIN_LOOP_NODES) / NUM_SORTERS;
            int rejoinIndex = (anchorIndex + 9 + (i % 13)) % MAIN_LOOP_NODES;

            createConveyor(mainLoopIds.get(anchorIndex), sorterIds.get(i),
                    10.0 + (i % 6), 2.0 + (i % 4) * 0.2, false, false);
            createConveyor(sorterIds.get(i), chuteIds.get(i),
                    8.0 + (i % 5), 1.5 + (i % 3) * 0.15, false, false);
            createConveyor(sorterIds.get(i), mainLoopIds.get(rejoinIndex),
                    14.0 + (i % 7), 2.8 + (i % 5) * 0.2, true, false);
        }
    }

    private void connectExpressLanes() throws Exception {
        for (int i = 0; i < EXPRESS_LANES; i++) {
            int fromIndex = (i * (MAIN_LOOP_NODES / EXPRESS_LANES)) % MAIN_LOOP_NODES;
            int skip = 35 + (i % 9) * 11;
            int toIndex = (fromIndex + skip) % MAIN_LOOP_NODES;

            createConveyor(mainLoopIds.get(fromIndex), mainLoopIds.get(toIndex),
                    22.0 + (i % 10) * 2.0, 4.5 + (i % 4) * 0.35, false, false);
        }
    }

    private void spawnItems(int count) throws Exception {
        for (int i = 0; i < count; i++) {
            String itemId = "PerfItem-" + itemCounter.incrementAndGet();
            String entranceId = entranceIds.get(random.nextInt(entranceIds.size()));
            String chuteId = chuteIds.get(random.nextInt(chuteIds.size()));

            Map<String, Object> properties = new HashMap<>();
            properties.put("lane", "L-" + (random.nextInt(24) + 1));
            properties.put("wave", random.nextInt(12));
            properties.put("priority", random.nextInt(10) == 0 ? "HIGH" : "NORMAL");
            properties.put("barcode", "P" + String.format("%011d", itemCounter.get()));
            properties.put("lengthCm", 20 + random.nextInt(90));

            sendEvent(new ItemCreatedEvent(itemId, itemId, 1.2 + random.nextDouble(), true, entranceId,
                    flunav.types.PositionType.LOCATION, 0.0, properties), "POST");
            sendEvent(new ItemDestinationEvent(itemId, chuteId), "PUT");
            createdItemIds.add(itemId);

            if ((i + 1) % 100 == 0) {
                logger.info("Spawned items: " + (i + 1) + "/" + count);
                Thread.sleep(50);
            }
        }
    }

    private void retargetItems(int count) throws Exception {
        if (createdItemIds.isEmpty()) {
            return;
        }

        for (int i = 0; i < count; i++) {
            String itemId = createdItemIds.get(random.nextInt(createdItemIds.size()));
            String chuteId = chuteIds.get(random.nextInt(chuteIds.size()));
            sendEvent(new ItemDestinationEvent(itemId, chuteId), "PUT");
        }
    }

    private void teleportItems(int count) throws Exception {
        if (createdItemIds.isEmpty() || teleportTargets.isEmpty()) {
            return;
        }

        for (int i = 0; i < count; i++) {
            String itemId = createdItemIds.get(random.nextInt(createdItemIds.size()));
            String target = teleportTargets.get(random.nextInt(teleportTargets.size()));
            sendEvent(new ItemPositionChangedEvent(itemId, target, 0.0), "PUT");
        }
    }

    private void emptyRandomChutes(int count) throws Exception {
        for (int i = 0; i < count; i++) {
            String chuteId = chuteIds.get(random.nextInt(chuteIds.size()));
            sendEvent(new ChuteEmptyEvent(chuteId), "PUT");
        }
    }
}
