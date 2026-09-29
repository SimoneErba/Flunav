package com.flunav.backend.services;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

import org.springframework.stereotype.Service;

import com.flunav.backend.models.multisimulation.ArrivalConfiguration;
import com.flunav.backend.models.multisimulation.ArrivalDistribution;
import com.flunav.backend.models.multisimulation.ConveyorFailureConfiguration;
import com.flunav.backend.models.multisimulation.DestinationProbability;
import com.flunav.backend.models.multisimulation.MultiSimulationConfiguration;

import flunav.events.ConnectionActivatedEvent;
import flunav.events.ConnectionDeactivatedEvent;
import flunav.events.DomainEvent;
import flunav.events.ItemCreatedEvent;
import flunav.types.PositionType;

@Service
public class MultiSimulationRandomGenerator {
    private static final int MAX_PLANNED_INPUT_EVENTS = 1_000_000;

    /** Generates every stochastic input before the virtual run starts. */
    public GeneratedInputs generate(MultiSimulationConfiguration configuration, long seed, String runIdentity) {
        SplittableRandom random = new SplittableRandom(seed);
        boolean controlled = "2".equals(configuration.inputGeneratorVersion());
        if (configuration.inputGeneratorVersion() != null && !"1".equals(configuration.inputGeneratorVersion()) && !controlled) {
            throw new IllegalArgumentException("Unsupported input generator version");
        }
        SplittableRandom rateRandom = controlled ? stream(seed, "arrival-rate") : random;
        SplittableRandom intervalRandom = controlled ? stream(seed, "arrival-interval") : random;
        SplittableRandom destinationRandom = controlled ? stream(seed, "destination") : random;
        double effectiveRate = effectiveArrivalRate(configuration.arrival(), rateRandom);
        Instant start = configuration.simulationStartTime();
        Instant end = start.plusSeconds(configuration.simulationDurationSeconds());
        List<DomainEvent> events = new ArrayList<>();

        Instant arrival = start;
        long itemSequence = 0;
        while (true) {
            arrival = arrival.plusNanos(nextIntervalNanos(configuration.arrival().distribution(), effectiveRate, intervalRandom));
            if (arrival.isAfter(end)) {
                break;
            }
            String destination = selectDestination(configuration.destinations(), destinationRandom);
            String itemId = runIdentity + "_item_" + itemSequence++;
            events.add(new ItemCreatedEvent(
                    itemId,
                    itemId,
                    1.0,
                    0.0,
                    true,
                    configuration.sourceLocationId(),
                    PositionType.LOCATION,
                    0.0,
                    List.of(destination),
                    controlled ? java.util.Map.of("multiSimulationDestination", destination, "lengthCm", 15.0)
                            : java.util.Map.of("multiSimulationDestination", destination),
                    arrival));
            if (events.size() > MAX_PLANNED_INPUT_EVENTS) {
                throw new IllegalArgumentException("Configuration generates too many planned input events");
            }
        }

        for (ConveyorFailureConfiguration failure : configuration.conveyorFailures()) {
            generateFailures(events, failure, start, end, controlled ? stream(seed, "failure:" + failure.conveyorId()) : random);
        }
        events.sort(java.util.Comparator.comparing(DomainEvent::getTimestamp));
        return new GeneratedInputs(effectiveRate, List.copyOf(events), itemSequence);
    }

    /** SHA-256 of decimal run seed, newline, and UTF-8 purpose; first eight bytes are the stream seed. */
    private SplittableRandom stream(long seed, String purpose) {
        try {
            byte[] bytes = java.security.MessageDigest.getInstance("SHA-256").digest(
                    (Long.toString(seed) + "\n" + purpose).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return new SplittableRandom(java.nio.ByteBuffer.wrap(bytes).getLong());
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private double effectiveArrivalRate(ArrivalConfiguration configuration, SplittableRandom random) {
        double variation = configuration.rateVariationPercent() / 100.0;
        if (variation == 0.0) {
            return configuration.ratePerHour();
        }
        return configuration.ratePerHour() * (1.0 + random.nextDouble(-variation, variation));
    }

    private long nextIntervalNanos(
            ArrivalDistribution distribution,
            double ratePerHour,
            SplittableRandom random) {
        double intervalSeconds = distribution == ArrivalDistribution.FIXED
                ? 3600.0 / ratePerHour
                : -Math.log1p(-random.nextDouble()) / (ratePerHour / 3600.0);
        return Math.max(1L, Math.round(intervalSeconds * 1_000_000_000.0));
    }

    private String selectDestination(List<DestinationProbability> destinations, SplittableRandom random) {
        double draw = random.nextDouble();
        double cumulative = 0.0;
        for (DestinationProbability destination : destinations) {
            cumulative += destination.probability();
            if (draw < cumulative) {
                return destination.destination();
            }
        }
        return destinations.getLast().destination();
    }

    private void generateFailures(
            List<DomainEvent> events,
            ConveyorFailureConfiguration configuration,
            Instant start,
            Instant end,
            SplittableRandom random) {
        Instant cursor = start;
        while (true) {
            double seconds = -Math.log1p(-random.nextDouble()) / (configuration.failuresPerHour() / 3600.0);
            cursor = cursor.plusNanos(Math.max(1L, Math.round(seconds * 1_000_000_000.0)));
            if (!cursor.isBefore(end)) {
                return;
            }
            events.add(new ConnectionDeactivatedEvent(configuration.conveyorId(), cursor));
            ensurePlannedEventLimit(events);
            Long repairSeconds = configuration.repairDurationSeconds();
            if (repairSeconds == null) {
                return;
            }
            cursor = cursor.plus(Duration.ofSeconds(repairSeconds));
            if (cursor.isBefore(end)) {
                events.add(new ConnectionActivatedEvent(configuration.conveyorId(), cursor));
                ensurePlannedEventLimit(events);
            }
        }
    }

    private void ensurePlannedEventLimit(List<DomainEvent> events) {
        if (events.size() > MAX_PLANNED_INPUT_EVENTS) {
            throw new IllegalArgumentException("Configuration generates too many planned input events");
        }
    }

    public record GeneratedInputs(double effectiveArrivalRate, List<DomainEvent> events, long itemCount) {
    }
}
