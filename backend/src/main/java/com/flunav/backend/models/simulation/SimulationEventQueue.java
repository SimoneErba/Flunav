package com.flunav.backend.models.simulation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.stream.Stream;

import flunav.events.DomainEvent;

/** Keeps replaced projections out of reads without scanning the pending queue on every replacement. */
public class SimulationEventQueue extends PriorityBlockingQueue<DomainEvent> {
    private final Set<DomainEvent> stale = ConcurrentHashMap.newKeySet();

    public SimulationEventQueue(Comparator<DomainEvent> comparator) {
        super(11, comparator);
    }

    public void invalidate(DomainEvent event) {
        if (event != null) {
            stale.add(event);
        }
    }

    @Override
    public DomainEvent peek() {
        while (true) {
            DomainEvent event = super.peek();
            if (event == null || !stale.contains(event)) {
                return event;
            }
            if (super.remove(event)) {
                stale.remove(event);
            }
        }
    }

    @Override
    public DomainEvent poll() {
        DomainEvent event;
        while ((event = super.poll()) != null) {
            if (!stale.remove(event)) {
                return event;
            }
        }
        return null;
    }

    @Override
    public Iterator<DomainEvent> iterator() {
        return new ArrayList<>(super.stream().filter(event -> !stale.contains(event)).toList()).iterator();
    }

    @Override
    public Stream<DomainEvent> stream() {
        return super.stream().filter(event -> !stale.contains(event));
    }

    @Override
    public int size() {
        return (int) stream().count();
    }
}
