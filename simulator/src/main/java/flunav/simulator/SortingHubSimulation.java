package flunav.simulator;

import flunav.types.LocationType;

import java.util.List;

final class SortingHubSimulation extends RoutingScenarioSimulation {
    SortingHubSimulation() {
        super(buildTopology());
    }

    private static ScenarioTopology buildTopology() {
        ScenarioTopology topology = new ScenarioTopology("sorting-hub", "SH-ITEM-");

        for (int index = 0; index < 4; index++) {
            double latitude = -45 + index * 30;
            topology.location("SH-ENTRY-" + index, latitude, -120, LocationType.JUNCTION, "induction");
            topology.location("SH-ACC-" + index, latitude, -100, LocationType.ACCUMULATION, "induction");
            topology.location("SH-INJECT-" + index, latitude, -80, LocationType.JUNCTION, "induction");
            topology.conveyor("SH-ENTRY-" + index, "SH-ACC-" + index, 12, 1.8, true, "induction");
            topology.conveyor("SH-ACC-" + index, "SH-INJECT-" + index, 10, 1.5, true, "accumulation");
        }

        for (int index = 0; index < 12; index++) {
            double longitude = -55 + index * 10;
            topology.location("SH-UP-" + index, 18, longitude, LocationType.JUNCTION, "upper-sorter");
            topology.location("SH-LOW-" + index, -18, longitude, LocationType.JUNCTION, "lower-sorter");
        }

        for (int index = 0; index < 10; index++) {
            double latitude = 70 - index * 15;
            topology.location("SH-GATE-" + index, latitude, 70, LocationType.JUNCTION, "chute-bank");
            topology.location("SH-CHUTE-" + index, latitude, 95, LocationType.CHUTE, "chute-bank");
            topology.conveyor("SH-GATE-" + index, "SH-CHUTE-" + index, 9, 1.2, true, "chute");
        }

        for (int index = 0; index < 8; index++) {
            topology.location(
                    "SH-RETURN-" + index,
                    -65,
                    55 - index * 18,
                    index == 3 ? LocationType.ACCUMULATION : LocationType.JUNCTION,
                    "recirculation");
        }

        for (int index = 0; index < 4; index++) {
            topology.location(
                    "SH-XOVER-" + index,
                    index % 2 == 0 ? 4 : -4,
                    -25 + index * 25,
                    LocationType.JUNCTION,
                    "crossover");
        }

        for (int index = 0; index < 4; index++) {
            boolean upperPrimary = index < 2;
            topology.conveyor(
                    "SH-INJECT-" + index,
                    upperPrimary ? "SH-UP-0" : "SH-LOW-0",
                    8,
                    2.4,
                    false,
                    "primary");
            topology.conveyor(
                    "SH-INJECT-" + index,
                    upperPrimary ? "SH-LOW-0" : "SH-UP-0",
                    14,
                    1.6,
                    true,
                    "fallback");
        }

        for (int index = 0; index < 11; index++) {
            boolean upperCritical = index == 4;
            boolean lowerCritical = index == 7;
            topology.conveyor(
                    "SH-UP-" + index,
                    "SH-UP-" + (index + 1),
                    8,
                    2.2,
                    !upperCritical,
                    upperCritical ? "critical-primary" : "sorter");
            topology.conveyor(
                    "SH-LOW-" + index,
                    "SH-LOW-" + (index + 1),
                    8,
                    2.0,
                    !lowerCritical,
                    lowerCritical ? "critical-primary" : "sorter");
        }

        topology.conveyor("SH-UP-2", "SH-XOVER-0", 9, 1.8, false, "alternate");
        topology.conveyor("SH-XOVER-0", "SH-LOW-3", 9, 1.8, true, "alternate");
        topology.conveyor("SH-LOW-2", "SH-XOVER-1", 10, 1.7, false, "alternate");
        topology.conveyor("SH-XOVER-1", "SH-UP-3", 10, 1.7, true, "alternate");
        topology.conveyor("SH-UP-6", "SH-XOVER-2", 9, 1.8, false, "alternate");
        topology.conveyor("SH-XOVER-2", "SH-LOW-7", 9, 1.8, true, "alternate");
        topology.conveyor("SH-LOW-6", "SH-XOVER-3", 10, 1.7, false, "alternate");
        topology.conveyor("SH-XOVER-3", "SH-UP-7", 10, 1.7, true, "alternate");

        topology.conveyor("SH-UP-4", "SH-LOW-5", 13, 1.6, true, "failure-bypass");
        topology.conveyor("SH-LOW-5", "SH-UP-5", 12, 1.6, false, "failure-bypass");
        topology.conveyor("SH-LOW-7", "SH-UP-8", 13, 1.6, true, "failure-bypass");
        topology.conveyor("SH-UP-8", "SH-LOW-8", 12, 1.6, false, "failure-bypass");

        int[] upperSources = { 4, 6, 8, 10, 11 };
        int[] lowerSources = { 4, 6, 8, 10, 11 };
        for (int index = 0; index < 5; index++) {
            topology.conveyor(
                    "SH-UP-" + upperSources[index],
                    "SH-GATE-" + index,
                    11 + index,
                    2.5,
                    false,
                    "primary-exit");
            topology.conveyor(
                    "SH-LOW-" + lowerSources[index],
                    "SH-GATE-" + (index + 5),
                    12 + index,
                    2.3,
                    false,
                    "primary-exit");
        }

        topology.conveyor("SH-UP-11", "SH-RETURN-0", 14, 2.0, true, "recirculation");
        topology.conveyor("SH-LOW-11", "SH-RETURN-0", 14, 2.0, true, "recirculation");
        for (int index = 0; index < 7; index++) {
            topology.conveyor(
                    "SH-RETURN-" + index,
                    "SH-RETURN-" + (index + 1),
                    10,
                    1.7,
                    true,
                    "recirculation");
        }
        topology.conveyor("SH-RETURN-7", "SH-UP-0", 16, 2.0, true, "recirculation");
        topology.conveyor("SH-RETURN-4", "SH-LOW-0", 18, 1.7, false, "recirculation-alternate");

        topology.entries("SH-ENTRY-0", "SH-ENTRY-1", "SH-ENTRY-2", "SH-ENTRY-3");
        topology.exits(
                "SH-CHUTE-0", "SH-CHUTE-1", "SH-CHUTE-2", "SH-CHUTE-3", "SH-CHUTE-4",
                "SH-CHUTE-5", "SH-CHUTE-6", "SH-CHUTE-7", "SH-CHUTE-8", "SH-CHUTE-9");

        topology.alternateRoute(
                "SH-ENTRY-0", "SH-ACC-0", "SH-INJECT-0", "SH-UP-0", "SH-UP-1", "SH-UP-2",
                "SH-XOVER-0", "SH-LOW-3", "SH-LOW-4", "SH-GATE-5", "SH-CHUTE-5");
        topology.alternateRoute(
                "SH-ENTRY-3", "SH-ACC-3", "SH-INJECT-3", "SH-LOW-0", "SH-LOW-1", "SH-LOW-2",
                "SH-XOVER-1", "SH-UP-3", "SH-UP-4", "SH-GATE-0", "SH-CHUTE-0");
        topology.alternateRoute(
                "SH-ENTRY-1", "SH-ACC-1", "SH-INJECT-1", "SH-UP-0", "SH-UP-1", "SH-UP-2",
                "SH-UP-3", "SH-UP-4", "SH-LOW-5", "SH-LOW-6", "SH-GATE-6", "SH-CHUTE-6");

        topology.reacquisitionRoute(
                "SH-UP-6", "SH-UP-7", "SH-UP-8", "SH-UP-9", "SH-UP-10", "SH-GATE-3", "SH-CHUTE-3");
        topology.reacquisitionRoute(
                "SH-LOW-6", "SH-LOW-7", "SH-LOW-8", "SH-LOW-9", "SH-LOW-10", "SH-GATE-8",
                "SH-CHUTE-8");

        topology.failure(
                "SH-UP-4",
                "SH-UP-5",
                List.of("SH-UP-4", "SH-LOW-5", "SH-UP-5"),
                List.of("SH-UP-4", "SH-UP-5", "SH-UP-6", "SH-UP-7", "SH-UP-8", "SH-GATE-2", "SH-CHUTE-2"),
                "SH-UPPER-DRIVE");
        topology.failure(
                "SH-LOW-7",
                "SH-LOW-8",
                List.of("SH-LOW-7", "SH-UP-8", "SH-LOW-8"),
                List.of("SH-LOW-7", "SH-LOW-8", "SH-LOW-9", "SH-LOW-10", "SH-GATE-8", "SH-CHUTE-8"),
                "SH-LOWER-DRIVE");

        if (topology.locationCount() != 68 || topology.conveyorCount() != 81) {
            throw new IllegalStateException("Unexpected sorting-hub topology size");
        }
        return topology;
    }
}
