package com.flunav.backend.services;

import flunav.events.DomainEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class LiveSystemScheduler {
    private static final Logger logger = LoggerFactory.getLogger(LiveSystemScheduler.class);

    private final EventProcessor eventProcessor;
    private final TimeService timeService;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(100,
            Thread.ofVirtual().factory());

    private static final int LOCK_STRIPES = 256;

    private static final class ScheduledTask {
        private final DomainEvent event;
        private final AtomicReference<ScheduledFuture<?>> future = new AtomicReference<>();
        private final AtomicBoolean cancelled = new AtomicBoolean(false);

        private ScheduledTask(DomainEvent event) {
            this.event = event;
        }

        private void setFuture(ScheduledFuture<?> scheduledFuture) {
            future.set(scheduledFuture);
            if (cancelled.get()) {
                scheduledFuture.cancel(false);
            }
        }

        private void cancel() {
            cancelled.set(true);
            ScheduledFuture<?> scheduledFuture = future.get();
            if (scheduledFuture != null) {
                scheduledFuture.cancel(false);
            }
        }
    }

    private final Map<String, ScheduledTask> scheduledTasksByItem = new ConcurrentHashMap<>();
    private final Object[] itemLocks = new Object[LOCK_STRIPES];

    public LiveSystemScheduler(@Lazy EventProcessor eventProcessor, TimeService timeService) {
        this.eventProcessor = eventProcessor;
        this.timeService = timeService;
        for (int index = 0; index < itemLocks.length; index++) {
            itemLocks[index] = new Object();
        }
    }

    /**
     * Schedules the next live internal event for an entity.
     * Existing same-item tasks are cancelled first so route changes, speed changes,
     * or blocking logic cannot leave stale future movement events in flight.
     */
    public void scheduleInternalEvent(DomainEvent event) {
        if (!(event instanceof flunav.events.EntityEvent ee)) {
            return;
        }

        String itemId = ee.getEntityId();
        synchronized (itemLock(itemId)) {
            cancelInternalEventLocked(itemId);

            Instant now = timeService.physicalNow();
            long delay = Duration.between(now, event.getTimestamp()).toMillis();

            if (delay <= 0) {
                eventProcessor.process(event, true)
                        .whenComplete((result, error) -> logProcessingFailure(event, error));
                return;
            }

            ScheduledTask task = new ScheduledTask(event);
            scheduledTasksByItem.put(itemId, task);
            ScheduledFuture<?> future = scheduler.schedule(() -> {
                synchronized (itemLock(itemId)) {
                    if (!scheduledTasksByItem.remove(itemId, task)) {
                        return;
                    }
                    try {
                        eventProcessor.process(event, true).join();
                    } catch (Exception e) {
                        logger.error("Error processing scheduled live event: {}", event.getEventType(), e);
                    }
                }
            }, delay, TimeUnit.MILLISECONDS);
            task.setFuture(future);
        }
    }

    private void logProcessingFailure(DomainEvent event, Throwable error) {
        if (error != null) {
            logger.error("Error processing immediate scheduled live event: {}", event.getEventType(), error);
        }
    }

    private Object itemLock(String itemId) {
        return itemLocks[Math.floorMod(itemId.hashCode(), itemLocks.length)];
    }

    private void cancelInternalEventLocked(String itemId) {
        ScheduledTask task = scheduledTasksByItem.remove(itemId);
        if (task != null) {
            task.cancel();
        }
    }

    /**
     * Cancels the pending live movement event for an item.
     * This is used when an item stops, leaves the system, or is rerouted before its
     * previously projected event should fire.
     */
    public void cancelInternalEvent(String itemId) {
        synchronized (itemLock(itemId)) {
            cancelInternalEventLocked(itemId);
        }
    }

    /**
     * Cancels every pending live event so test resets and application shutdown
     * cannot execute movement work against state that is being destroyed.
     */
    public void cancelAll() {
        scheduledTasksByItem.values().forEach(ScheduledTask::cancel);
        scheduledTasksByItem.clear();
    }

    public DomainEvent getScheduledEvent(String itemId) {
        ScheduledTask task = scheduledTasksByItem.get(itemId);
        return task != null ? task.event : null;
    }

    @PreDestroy
    public void shutdown() {
        cancelAll();
        scheduler.shutdownNow();
    }
}
