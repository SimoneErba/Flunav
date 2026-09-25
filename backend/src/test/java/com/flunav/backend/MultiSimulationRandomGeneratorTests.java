package com.flunav.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.flunav.backend.models.multisimulation.ArrivalConfiguration;
import com.flunav.backend.models.multisimulation.ArrivalDistribution;
import com.flunav.backend.models.multisimulation.ConveyorFailureConfiguration;
import com.flunav.backend.models.multisimulation.DestinationProbability;
import com.flunav.backend.models.multisimulation.MultiSimulationConfiguration;
import com.flunav.backend.services.MultiSimulationRandomGenerator;

import flunav.events.ConnectionActivatedEvent;
import flunav.events.ConnectionDeactivatedEvent;
import flunav.events.ItemCreatedEvent;

class MultiSimulationRandomGeneratorTests {
    private final MultiSimulationRandomGenerator generator = new MultiSimulationRandomGenerator();

    @Test
    void sameSeedProducesSameArrivalsDestinationsAndFailures() {
        MultiSimulationConfiguration configuration = configuration(ArrivalDistribution.POISSON, 3600, 20,
                List.of(new ConveyorFailureConfiguration("conveyor-1", 20, 10L)));

        var first = generator.generate(configuration, 42, "run");
        var second = generator.generate(configuration, 42, "run");

        assertEquals(first.effectiveArrivalRate(), second.effectiveArrivalRate());
        assertEquals(first.events().size(), second.events().size());
        for (int index = 0; index < first.events().size(); index++) {
            assertEquals(first.events().get(index).getClass(), second.events().get(index).getClass());
            assertEquals(first.events().get(index).getTimestamp(), second.events().get(index).getTimestamp());
            if (first.events().get(index) instanceof ItemCreatedEvent firstItem) {
                ItemCreatedEvent secondItem = (ItemCreatedEvent) second.events().get(index);
                assertEquals(firstItem.getEntityId(), secondItem.getEntityId());
                assertEquals(firstItem.getDestinations(), secondItem.getDestinations());
            }
        }
    }

    @Test
    void differentSeedsProduceDifferentPoissonArrivals() {
        MultiSimulationConfiguration configuration = configuration(ArrivalDistribution.POISSON, 3600, 0, List.of());
        var first = generator.generate(configuration, 1, "run");
        var second = generator.generate(configuration, 2, "run");

        List<Instant> firstArrivals = first.events().stream()
                .filter(ItemCreatedEvent.class::isInstance).map(event -> event.getTimestamp()).toList();
        List<Instant> secondArrivals = second.events().stream()
                .filter(ItemCreatedEvent.class::isInstance).map(event -> event.getTimestamp()).toList();
        assertFalse(firstArrivals.equals(secondArrivals));
    }

    @Test
    void fixedGeneratorProducesExactIntervals() {
        MultiSimulationConfiguration configuration = configuration(ArrivalDistribution.FIXED, 1200, 0, List.of());
        var inputs = generator.generate(configuration, 10, "run");
        List<Instant> arrivals = inputs.events().stream()
                .filter(ItemCreatedEvent.class::isInstance).map(event -> event.getTimestamp()).toList();

        assertFalse(arrivals.isEmpty());
        assertEquals(20, arrivals.size());
        assertEquals(configuration.simulationStartTime().plusSeconds(3), arrivals.getFirst());
        assertEquals(configuration.simulationStartTime().plusSeconds(60), arrivals.getLast());
        for (int index = 1; index < arrivals.size(); index++) {
            assertEquals(Duration.ofSeconds(3), Duration.between(arrivals.get(index - 1), arrivals.get(index)));
        }
    }

    @Test
    void plannedFailureIsFollowedByRepairAtConfiguredVirtualDelay() {
        MultiSimulationConfiguration configuration = configuration(ArrivalDistribution.FIXED, 1, 0,
                List.of(new ConveyorFailureConfiguration("conveyor-1", 10_000, 15L)));
        var events = generator.generate(configuration, 9, "run").events();
        int failureIndex = -1;
        for (int index = 0; index < events.size(); index++) {
            if (events.get(index) instanceof ConnectionDeactivatedEvent) {
                failureIndex = index;
                break;
            }
        }

        assertTrue(failureIndex >= 0);
        assertInstanceOf(ConnectionActivatedEvent.class, events.get(failureIndex + 1));
        assertEquals(Duration.ofSeconds(15),
                Duration.between(events.get(failureIndex).getTimestamp(), events.get(failureIndex + 1).getTimestamp()));
    }

    private MultiSimulationConfiguration configuration(
            ArrivalDistribution distribution,
            double rate,
            double variation,
            List<ConveyorFailureConfiguration> failures) {
        return new MultiSimulationConfiguration(
                "Test",
                60,
                2,
                new ArrivalConfiguration(rate, distribution, variation),
                "source",
                List.of(new DestinationProbability("A", 0.5), new DestinationProbability("B", 0.5)),
                failures,
                123L,
                Instant.parse("2030-01-01T00:00:00Z"));
    }
}
