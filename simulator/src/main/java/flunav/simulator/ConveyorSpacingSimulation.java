package flunav.simulator;

import flunav.events.ConnectionCreatedEvent;
import flunav.events.ItemCreatedEvent;
import flunav.events.ItemDeletedEvent;
import flunav.types.ConveyorType;
import flunav.types.LocationType;
import flunav.types.PositionType;
import java.util.List;
import java.util.Map;

/** A bounded two-feeder merge that exposes belt and roller admission pauses. */
final class ConveyorSpacingSimulation implements Simulation {
    private static final String BELT_SOURCE = "CS-BELT-SOURCE";
    private static final String ROLLER_SOURCE = "CS-ROLLER-SOURCE";
    private static final String MERGE = "CS-MERGE";
    private static final String EXIT = "CS-EXIT";
    private static final int ITEMS_PER_FEEDER = 4;

    @Override
    public void setup() throws Exception {
        requireRabbit();
        SimulatorUtils.createLocation(BELT_SOURCE, 0, 0, LocationType.JUNCTION);
        SimulatorUtils.createLocation(ROLLER_SOURCE, 8, 0, LocationType.JUNCTION);
        SimulatorUtils.createLocation(MERGE, 4, 8, LocationType.JUNCTION);
        SimulatorUtils.createLocation(EXIT, 4, 15, LocationType.CHUTE);
        Thread.sleep(1000);
        conveyor(BELT_SOURCE, MERGE, 3.0, 0.5, ConveyorType.BELT);
        conveyor(ROLLER_SOURCE, MERGE, 3.0, 0.5, ConveyorType.ROLLER);
        conveyor(MERGE, EXIT, 1.5, 0.05, ConveyorType.BELT);
        Thread.sleep(1000);
    }

    @Override
    public void run() throws Exception {
        requireRabbit();
        item("OUTLET", conveyorId(MERGE, EXIT), 1, 0.0);
        for (int index = 1; index <= ITEMS_PER_FEEDER; index++) {
            item("BELT", conveyorId(BELT_SOURCE, MERGE), index, 90.0 - (index - 1) * 25.0);
            item("ROLLER", conveyorId(ROLLER_SOURCE, MERGE), index, 90.0 - (index - 1) * 25.0);
        }
        Thread.sleep(65000);
        SimulatorUtils.logger.info("Conveyor spacing burst finished; run destroy to remove its topology and items.");
    }

    @Override
    public void destroy() throws Exception {
        requireRabbit();
        for (int index = 1; index <= ITEMS_PER_FEEDER; index++) {
            SimulatorUtils.sendEvent(new ItemDeletedEvent(itemId("BELT", index)), "DELETE");
            SimulatorUtils.sendEvent(new ItemDeletedEvent(itemId("ROLLER", index)), "DELETE");
        }
        SimulatorUtils.sendEvent(new ItemDeletedEvent(itemId("OUTLET", 1)), "DELETE");
        SimulatorUtils.deleteConveyor(MERGE, EXIT);
        SimulatorUtils.deleteConveyor(BELT_SOURCE, MERGE);
        SimulatorUtils.deleteConveyor(ROLLER_SOURCE, MERGE);
        SimulatorUtils.deleteLocation(EXIT);
        SimulatorUtils.deleteLocation(MERGE);
        SimulatorUtils.deleteLocation(BELT_SOURCE);
        SimulatorUtils.deleteLocation(ROLLER_SOURCE);
    }

    private void conveyor(String from, String to, double length, double speed, ConveyorType type) throws Exception {
        String id = conveyorId(from, to);
        SimulatorUtils.sendEvent(new ConnectionCreatedEvent(id, from, to, length, speed, 0.05,
                (long) (length / speed * 1000), true, id, true, type, 100, Map.of("scenario", "conveyor-spacing")), "POST");
    }

    private void item(String feeder, String conveyor, int index, double progress) throws Exception {
        Map<String, Object> properties = Map.of("lengthCm", 20, "scenario", "conveyor-spacing");
        String id = itemId(feeder, index);
        SimulatorUtils.sendEvent(new ItemCreatedEvent(id, id, 1.0, 0.0, true, conveyor,
                PositionType.CONVEYOR, progress, List.of(EXIT), properties), "POST");
    }

    private String conveyorId(String from, String to) {
        return "Conveyor_" + from + "_" + to;
    }

    private String itemId(String feeder, int index) {
        return "CS-" + feeder + "-" + index;
    }

    private void requireRabbit() {
        if (!SimulatorUtils.MODE.equalsIgnoreCase("rabbit")) {
            throw new IllegalStateException("Conveyor spacing scenario requires SIMULATION_MODE=rabbit");
        }
    }
}
