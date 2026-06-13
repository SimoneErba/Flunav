package flunav.simulator;

import flunav.types.LocationType;

import java.util.ArrayList;
import java.util.List;

final class AirportBaggageSimulation extends RoutingScenarioSimulation {
    AirportBaggageSimulation() {
        super(buildTopology());
    }

    private static ScenarioTopology buildTopology() {
        ScenarioTopology topology = new ScenarioTopology("airport-baggage", "AB-BAG-");

        for (int index = 0; index < 4; index++) {
            double latitude = -45 + index * 30;
            topology.location("AB-CHECKIN-" + index, latitude, -125, LocationType.JUNCTION, "check-in");
            topology.location("AB-TAG-" + index, latitude, -105, LocationType.ACCUMULATION, "check-in");
            topology.location("AB-INJECT-" + index, latitude, -85, LocationType.JUNCTION, "check-in");
            topology.conveyor("AB-CHECKIN-" + index, "AB-TAG-" + index, 11, 1.5, true, "check-in");
            topology.conveyor("AB-TAG-" + index, "AB-INJECT-" + index, 9, 1.3, true, "tagging");
        }

        for (int index = 0; index < 12; index++) {
            double longitude = -60 + index * 10;
            topology.location("AB-PRIMARY-" + index, 22, longitude, LocationType.JUNCTION, "primary-screening");
            topology.location("AB-REDUNDANT-" + index, -22, longitude, LocationType.JUNCTION,
                    "redundant-screening");
            topology.location(
                    "AB-TRANSFER-" + index,
                    0,
                    70 + index * 10,
                    index == 2 || index == 8 ? LocationType.ACCUMULATION : LocationType.JUNCTION,
                    "transfer");
        }

        for (int index = 0; index < 8; index++) {
            double latitude = 60 - index * 17;
            topology.location("AB-MAKEUP-" + index, latitude, 200, LocationType.JUNCTION, "make-up");
            topology.location("AB-EXIT-" + index, latitude, 225, LocationType.CHUTE, "make-up");
            topology.conveyor("AB-MAKEUP-" + index, "AB-EXIT-" + index, 10, 1.1, true, "make-up");
            topology.location(
                    "AB-RETURN-" + index,
                    -75,
                    170 - index * 24,
                    index == 4 ? LocationType.ACCUMULATION : LocationType.JUNCTION,
                    "exception-return");
        }

        for (int index = 0; index < 4; index++) {
            boolean primaryRoute = index < 2;
            topology.conveyor(
                    "AB-INJECT-" + index,
                    primaryRoute ? "AB-PRIMARY-0" : "AB-REDUNDANT-0",
                    7,
                    2.2,
                    false,
                    "primary");
            topology.conveyor(
                    "AB-INJECT-" + index,
                    primaryRoute ? "AB-REDUNDANT-0" : "AB-PRIMARY-0",
                    13,
                    1.5,
                    true,
                    "fallback");
        }

        for (int index = 0; index < 11; index++) {
            boolean primaryCritical = index == 4;
            boolean redundantCritical = index == 7;
            topology.conveyor(
                    "AB-PRIMARY-" + index,
                    "AB-PRIMARY-" + (index + 1),
                    9,
                    2.0,
                    !primaryCritical,
                    primaryCritical ? "critical-primary" : "screening");
            topology.conveyor(
                    "AB-REDUNDANT-" + index,
                    "AB-REDUNDANT-" + (index + 1),
                    10,
                    1.8,
                    !redundantCritical,
                    redundantCritical ? "critical-primary" : "screening");
        }

        topology.conveyor("AB-PRIMARY-2", "AB-REDUNDANT-3", 13, 1.6, false, "screening-crossover");
        topology.conveyor("AB-REDUNDANT-2", "AB-PRIMARY-3", 13, 1.6, false, "screening-crossover");
        topology.conveyor("AB-PRIMARY-6", "AB-REDUNDANT-7", 13, 1.6, false, "screening-crossover");
        topology.conveyor("AB-REDUNDANT-6", "AB-PRIMARY-7", 13, 1.6, false, "screening-crossover");

        topology.conveyor("AB-PRIMARY-4", "AB-REDUNDANT-5", 15, 1.5, true, "failure-bypass");
        topology.conveyor("AB-REDUNDANT-5", "AB-PRIMARY-5", 14, 1.5, false, "failure-bypass");
        topology.conveyor("AB-REDUNDANT-7", "AB-PRIMARY-8", 15, 1.5, true, "failure-bypass");
        topology.conveyor("AB-PRIMARY-8", "AB-REDUNDANT-8", 14, 1.5, false, "failure-bypass");

        topology.conveyor("AB-PRIMARY-11", "AB-TRANSFER-0", 12, 2.2, true, "screening-release");
        topology.conveyor("AB-PRIMARY-11", "AB-TRANSFER-6", 24, 1.7, false, "transfer-bypass");
        topology.conveyor("AB-REDUNDANT-11", "AB-TRANSFER-0", 12, 2.2, true, "screening-release");
        for (int index = 0; index < 11; index++) {
            boolean critical = index == 5;
            topology.conveyor(
                    "AB-TRANSFER-" + index,
                    "AB-TRANSFER-" + (index + 1),
                    8,
                    2.4,
                    !critical,
                    critical ? "critical-primary" : "transfer");
        }

        for (int index = 0; index < 8; index++) {
            topology.conveyor(
                    "AB-TRANSFER-" + (index + 4),
                    "AB-MAKEUP-" + index,
                    10 + index,
                    2.1,
                    false,
                    "destination-exit");
        }

        topology.conveyor("AB-TRANSFER-5", "AB-RETURN-0", 15, 1.8, true, "failure-bypass");
        topology.conveyor("AB-TRANSFER-11", "AB-RETURN-0", 14, 1.8, true, "recirculation");
        for (int index = 0; index < 7; index++) {
            topology.conveyor(
                    "AB-RETURN-" + index,
                    "AB-RETURN-" + (index + 1),
                    11,
                    1.6,
                    true,
                    "exception-return");
        }
        topology.conveyor("AB-RETURN-7", "AB-PRIMARY-0", 17, 1.8, true, "recirculation");
        topology.conveyor("AB-RETURN-7", "AB-REDUNDANT-0", 19, 1.6, false, "recirculation-alternate");

        topology.entries("AB-CHECKIN-0", "AB-CHECKIN-1", "AB-CHECKIN-2", "AB-CHECKIN-3");
        topology.exits(
                "AB-EXIT-0", "AB-EXIT-1", "AB-EXIT-2", "AB-EXIT-3",
                "AB-EXIT-4", "AB-EXIT-5", "AB-EXIT-6", "AB-EXIT-7");

        topology.alternateRoute(
                "AB-CHECKIN-0", "AB-TAG-0", "AB-INJECT-0", "AB-PRIMARY-0", "AB-PRIMARY-1",
                "AB-PRIMARY-2", "AB-REDUNDANT-3", "AB-REDUNDANT-4", "AB-REDUNDANT-5",
                "AB-REDUNDANT-6", "AB-REDUNDANT-7", "AB-PRIMARY-8", "AB-PRIMARY-9",
                "AB-PRIMARY-10", "AB-PRIMARY-11", "AB-TRANSFER-0", "AB-TRANSFER-1",
                "AB-TRANSFER-2", "AB-TRANSFER-3", "AB-TRANSFER-4", "AB-MAKEUP-0", "AB-EXIT-0");
        topology.alternateRoute(
                "AB-CHECKIN-3", "AB-TAG-3", "AB-INJECT-3", "AB-REDUNDANT-0", "AB-REDUNDANT-1",
                "AB-REDUNDANT-2", "AB-PRIMARY-3", "AB-PRIMARY-4", "AB-REDUNDANT-5",
                "AB-REDUNDANT-6", "AB-PRIMARY-7", "AB-PRIMARY-8", "AB-PRIMARY-9",
                "AB-PRIMARY-10", "AB-PRIMARY-11", "AB-TRANSFER-0", "AB-TRANSFER-1",
                "AB-TRANSFER-2", "AB-TRANSFER-3", "AB-TRANSFER-4", "AB-TRANSFER-5",
                "AB-RETURN-0", "AB-RETURN-1", "AB-RETURN-2", "AB-RETURN-3", "AB-RETURN-4",
                "AB-RETURN-5", "AB-RETURN-6", "AB-RETURN-7", "AB-PRIMARY-0", "AB-PRIMARY-1",
                "AB-PRIMARY-2", "AB-PRIMARY-3", "AB-PRIMARY-4", "AB-PRIMARY-5",
                "AB-PRIMARY-6", "AB-PRIMARY-7", "AB-PRIMARY-8", "AB-PRIMARY-9",
                "AB-PRIMARY-10", "AB-PRIMARY-11", "AB-TRANSFER-0", "AB-TRANSFER-1",
                "AB-TRANSFER-2", "AB-TRANSFER-3", "AB-TRANSFER-4", "AB-TRANSFER-5",
                "AB-TRANSFER-6", "AB-TRANSFER-7", "AB-MAKEUP-3", "AB-EXIT-3");

        topology.reacquisitionRoute(
                "AB-TRANSFER-2", "AB-TRANSFER-3", "AB-TRANSFER-4", "AB-TRANSFER-5",
                "AB-TRANSFER-6", "AB-MAKEUP-2", "AB-EXIT-2");
        topology.reacquisitionRoute(
                "AB-REDUNDANT-6", "AB-REDUNDANT-7", "AB-REDUNDANT-8", "AB-REDUNDANT-9",
                "AB-REDUNDANT-10", "AB-REDUNDANT-11", "AB-TRANSFER-0", "AB-TRANSFER-1",
                "AB-TRANSFER-2", "AB-TRANSFER-3", "AB-TRANSFER-4", "AB-TRANSFER-5",
                "AB-TRANSFER-6", "AB-TRANSFER-7", "AB-TRANSFER-8", "AB-MAKEUP-4", "AB-EXIT-4");

        topology.failure(
                "AB-PRIMARY-4",
                "AB-PRIMARY-5",
                List.of("AB-PRIMARY-4", "AB-REDUNDANT-5", "AB-PRIMARY-5"),
                List.of(
                        "AB-PRIMARY-4", "AB-PRIMARY-5", "AB-PRIMARY-6", "AB-PRIMARY-7",
                        "AB-PRIMARY-8", "AB-PRIMARY-9", "AB-PRIMARY-10", "AB-PRIMARY-11",
                        "AB-TRANSFER-0", "AB-TRANSFER-1", "AB-TRANSFER-2", "AB-TRANSFER-3",
                        "AB-TRANSFER-4", "AB-MAKEUP-0", "AB-EXIT-0"),
                "AB-PRIMARY-SCREEN");
        topology.failure(
                "AB-REDUNDANT-7",
                "AB-REDUNDANT-8",
                List.of("AB-REDUNDANT-7", "AB-PRIMARY-8", "AB-REDUNDANT-8"),
                List.of(
                        "AB-REDUNDANT-7", "AB-REDUNDANT-8", "AB-REDUNDANT-9", "AB-REDUNDANT-10",
                        "AB-REDUNDANT-11", "AB-TRANSFER-0", "AB-TRANSFER-1", "AB-TRANSFER-2",
                        "AB-TRANSFER-3", "AB-TRANSFER-4", "AB-TRANSFER-5", "AB-TRANSFER-6",
                        "AB-MAKEUP-2", "AB-EXIT-2"),
                "AB-REDUNDANT-SCREEN");
        topology.failure(
                "AB-TRANSFER-5",
                "AB-TRANSFER-6",
                transferBypassPath(),
                List.of("AB-TRANSFER-5", "AB-TRANSFER-6", "AB-TRANSFER-7", "AB-MAKEUP-3", "AB-EXIT-3"),
                "AB-TRANSFER-DRIVE");

        if (topology.locationCount() != 72 || topology.conveyorCount() != 87) {
            throw new IllegalStateException("Unexpected airport-baggage topology size");
        }
        return topology;
    }

    private static List<String> transferBypassPath() {
        List<String> path = new ArrayList<>();
        path.add("AB-TRANSFER-5");
        for (int index = 0; index < 8; index++) {
            path.add("AB-RETURN-" + index);
        }
        for (int index = 0; index < 12; index++) {
            path.add("AB-PRIMARY-" + index);
        }
        path.add("AB-TRANSFER-6");
        return path;
    }
}
