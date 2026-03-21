package com.flunav.backend.services;

import flunav.events.DomainEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.*;

@Service
public class LiveSystemScheduler {
    private static final Logger logger = LoggerFactory.getLogger(LiveSystemScheduler.class);

    private final EventProcessor eventProcessor;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(100,
            Thread.ofVirtual().factory());

    private final java.util.Map<String, ScheduledFuture<?>> scheduledTasksByItem = new java.util.concurrent.ConcurrentHashMap<>();

    public LiveSystemScheduler(@Lazy EventProcessor eventProcessor) {
        this.eventProcessor = eventProcessor;
    }

    public void scheduleInternalEvent(DomainEvent event) {
        if (!(event instanceof flunav.events.EntityEvent ee)) {
            return;
        }

        String itemId = ee.getEntityId();
        cancelInternalEvent(itemId);

        Instant now = Instant.now();
        long delay = Duration.between(now, event.getTimestamp()).toMillis();

        if (delay <= 0) {
            eventProcessor.process(event, true);
        } else {
            ScheduledFuture<?> future = scheduler.schedule(() -> {
                try {
                    scheduledTasksByItem.remove(itemId);
                    eventProcessor.process(event, true);
                } catch (Exception e) {
                    logger.error("Error processing scheduled live event: {}", event.getEventType(), e);
                }
            }, delay, TimeUnit.MILLISECONDS);
            scheduledTasksByItem.put(itemId, future);
        }
    }

    public void cancelInternalEvent(String itemId) {
        ScheduledFuture<?> future = scheduledTasksByItem.remove(itemId);
        if (future != null) {
            future.cancel(false);
        }
    }
}
