package com.flunav.backend.services;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.flunav.backend.context.DatabaseContextHolder;
import com.flunav.backend.models.simulation.SimulationState;

import flunav.events.ConnectionCreatedEvent;
import flunav.events.ItemCreatedEvent;
import flunav.events.LocationCreatedEvent;
import flunav.events.LocationDeletedEvent;
import flunav.types.ConveyorType;
import flunav.types.LocationType;
import flunav.types.PositionType;

/** Runs the simulator's conveyor-spacing merge in an isolated What If workspace. */
@Service
public class ConveyorSpacingDemoScenarioService {
    private static final String BELT_SOURCE = "CS-BELT-SOURCE";
    private static final String ROLLER_SOURCE = "CS-ROLLER-SOURCE";
    private static final String MERGE = "CS-MERGE";
    private static final String EXIT = "CS-EXIT";

    private final EventProcessor events;
    private final TopologyProvider topology;
    private final SimulationService simulations;
    private final TimeService timeService;

    public ConveyorSpacingDemoScenarioService(EventProcessor events, TopologyProvider topology,
            SimulationService simulations, TimeService timeService) {
        this.events = events;
        this.topology = topology;
        this.simulations = simulations;
        this.timeService = timeService;
    }

    /** Seeds a slow occupied outlet so both feeders visibly wait for merge admission. */
    public synchronized SimulationState start() {
        SimulationState simulation = simulations.createWhatIf(null);
        String id = simulation.getId();
        try {
            simulations.isolateFromLiveInput(id);
            Instant start = simulation.getLastProcessedTimestamp();
            try (var database = DatabaseContextHolder.enterSimulationContext(id);
                    var virtualTime = timeService.enterVirtualTime(start)) {
                topology.getAllLocations().stream().map(location -> location.getId()).toList()
                        .forEach(locationId -> events.process(new LocationDeletedEvent(locationId), true).join());
                location(BELT_SOURCE, "Belt feeder", 0, 0, LocationType.JUNCTION);
                location(ROLLER_SOURCE, "Roller feeder", 8, 0, LocationType.JUNCTION);
                location(MERGE, "Merge", 4, 8, LocationType.JUNCTION);
                location(EXIT, "Merge exit", 4, 15, LocationType.CHUTE);
                conveyor(BELT_SOURCE, MERGE, 3.0, 0.5, ConveyorType.BELT);
                conveyor(ROLLER_SOURCE, MERGE, 3.0, 0.5, ConveyorType.ROLLER);
                conveyor(MERGE, EXIT, 1.5, 0.05, ConveyorType.BELT);
                item("CS-OUTLET-1", conveyorId(MERGE, EXIT), 0.0, start);
                for (int index = 1; index <= 4; index++) {
                    double progress = 90.0 - (index - 1) * 25.0;
                    item("CS-BELT-" + index, conveyorId(BELT_SOURCE, MERGE), progress, start);
                    item("CS-ROLLER-" + index, conveyorId(ROLLER_SOURCE, MERGE), progress, start);
                }
            }
            simulations.startPlayback(id, 1.0);
            return simulation;
        } catch (RuntimeException failure) {
            simulations.destroySimulation(id);
            throw failure;
        }
    }

    private void location(String id, String name, double x, double y, LocationType type) {
        events.process(new LocationCreatedEvent(id, name, true, x, y, type, 100,
                Map.of("scenario", "conveyor-spacing")), true).join();
    }

    private void conveyor(String from, String to, double length, double speed, ConveyorType type) {
        String id = conveyorId(from, to);
        events.process(new ConnectionCreatedEvent(id, from, to, length, speed, 0.05,
                Math.round(length / speed * 1000), true, id, true, type, 100,
                Map.of("scenario", "conveyor-spacing")), true).join();
    }

    private void item(String id, String conveyor, double progress, Instant timestamp) {
        events.process(new ItemCreatedEvent(id, id, 1.0, 0.0, true, conveyor,
                PositionType.CONVEYOR, progress, List.of(EXIT),
                Map.of("lengthCm", 20, "scenario", "conveyor-spacing"), timestamp), true).join();
    }

    private String conveyorId(String from, String to) {
        return "Conveyor_" + from + "_" + to;
    }
}
