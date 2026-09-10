package com.flunav.backend.services;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.PriorityBlockingQueue;

import org.springframework.stereotype.Service;

import com.flunav.backend.models.simulation.EventOrigin;
import com.flunav.backend.models.simulation.SimulationKind;
import flunav.events.*;

/** Transfers committed live reductions to registered simulations before the live barrier is released. */
@Service
public class SimulationInputService {
    private final Map<String, Intake> intakes = new ConcurrentHashMap<>();

    private static final class Intake {
        final SimulationKind kind;
        final Set<String> seen = new HashSet<>();
        long sequence;
        final PriorityBlockingQueue<QueuedInput> events = new PriorityBlockingQueue<>(11,
                Comparator.comparing((QueuedInput input) -> input.event().getTimestamp())
                        .thenComparingLong(QueuedInput::sequence));

        Intake(SimulationKind kind) {
            this.kind = kind;
        }

        synchronized void add(DomainEvent event) {
            if (seen.add(event.getEventId())) events.add(new QueuedInput(event, sequence++));
        }
    }

    private record QueuedInput(DomainEvent event, long sequence) {
    }

    public void register(String simulationId, SimulationKind kind) {
        intakes.put(simulationId, new Intake(kind));
    }

    public void unregister(String simulationId) {
        intakes.remove(simulationId);
    }

    public boolean isRegistered(String simulationId) {
        return intakes.containsKey(simulationId);
    }

    public void onLiveEvent(DomainEvent event, EventOrigin origin) {
        intakes.values().forEach(intake -> {
            if (intake.kind == SimulationKind.STANDARD
                    || (origin == EventOrigin.EXTERNAL_INGESTION && acceptsWhatIf(event))) {
                intake.add(event);
            }
        });
    }

    public void addHistory(String simulationId, List<DomainEvent> events) {
        Intake intake = intakes.get(simulationId);
        if (intake == null) return;
        events.stream().filter(event -> intake.kind == SimulationKind.STANDARD || acceptsWhatIf(event))
                .forEach(intake::add);
    }

    public DomainEvent peek(String simulationId) {
        Intake intake = intakes.get(simulationId);
        QueuedInput input = intake == null ? null : intake.events.peek();
        return input == null ? null : input.event();
    }

    public DomainEvent poll(String simulationId) {
        Intake intake = intakes.get(simulationId);
        QueuedInput input = intake == null ? null : intake.events.poll();
        return input == null ? null : input.event();
    }

    public List<DomainEvent> drainThrough(String simulationId, Instant timestamp) {
        List<DomainEvent> result = new ArrayList<>();
        DomainEvent next;
        while ((next = peek(simulationId)) != null && !next.getTimestamp().isAfter(timestamp)) {
            result.add(poll(simulationId));
        }
        return result;
    }

    private boolean acceptsWhatIf(DomainEvent event) {
        return event instanceof ItemCreatedEvent || event instanceof ItemDeletedEvent
                || event instanceof ItemActivatedEvent || event instanceof ItemDeactivatedEvent
                || event instanceof ItemDestinationEvent || event instanceof ItemPathChangedEvent
                || event instanceof ItemPriorityUpdatedEvent || event instanceof ItemRenamedEvent
                || event instanceof ItemPropertiesUpdatedEvent;
    }
}
