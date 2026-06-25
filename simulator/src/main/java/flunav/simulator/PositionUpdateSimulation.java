package flunav.simulator;

import flunav.events.ItemCreatedEvent;
import flunav.events.ItemPositionChangedEvent;
import flunav.types.LocationType;

import java.util.HashMap;

import static flunav.simulator.SimulatorUtils.createConveyor;
import static flunav.simulator.SimulatorUtils.createLocation;
import static flunav.simulator.SimulatorUtils.deleteConveyor;
import static flunav.simulator.SimulatorUtils.deleteLocation;
import static flunav.simulator.SimulatorUtils.itemCounter;
import static flunav.simulator.SimulatorUtils.logger;
import static flunav.simulator.SimulatorUtils.sendEvent;

class PositionUpdateSimulation implements Simulation {
    @Override
    public void setup() throws Exception {
        logger.info("--- Setting up Jump/Update Simulation ---");

        createLocation("A", 0, -50, LocationType.JUNCTION);
        createLocation("B", 0, 50, LocationType.JUNCTION);
        createLocation("C", 50, 100, LocationType.CHUTE);

        logger.info("Waiting for nodes to persist...");
        Thread.sleep(1000);

        logger.info("--- Creating Connections ---");
        createConveyor("A", "B", 100.0, 5.0, true);
        createConveyor("B", "C", 80.0, 4.0, true);
    }

    @Override
    public void destroy() throws Exception {
        logger.info("--- Destroying Jump Simulation ---");
        deleteConveyor("A", "B");
        deleteConveyor("B", "C");
        deleteLocation("A");
        deleteLocation("B");
        deleteLocation("C");
    }

    @Override
    public void run() throws Exception {
        logger.info("--- Starting Position Jumps ---");
        String edge1 = "Conveyor_A_B";
        String edge2 = "Conveyor_B_C";

        while (true) {
            String itemId = "Jumper-" + itemCounter.incrementAndGet();

            logger.info("Creating " + itemId + " at A");
            sendEvent(new ItemCreatedEvent(itemId, itemId, 1.0, 0.0, true, "A", flunav.types.PositionType.LOCATION, 0.0,
                    new HashMap<>()), "POST");

            Thread.sleep(2000);

            logger.info("Jumping " + itemId + " to middle of " + edge1);
            sendEvent(new ItemPositionChangedEvent(itemId, edge1, 0.5), "PUT");

            Thread.sleep(2000);

            logger.info("Jumping " + itemId + " to end of " + edge1);
            sendEvent(new ItemPositionChangedEvent(itemId, edge1, 0.9), "PUT");

            Thread.sleep(5000);

            logger.info("Jumping " + itemId + " to node B");
            sendEvent(new ItemPositionChangedEvent(itemId, "B", 0.0), "PUT");

            Thread.sleep(2000);

            logger.info("Jumping " + itemId + " to start of " + edge2);
            sendEvent(new ItemPositionChangedEvent(itemId, edge2, 0.1), "PUT");
            Thread.sleep(5000);
        }
    }
}
