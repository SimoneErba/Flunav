package com.flunav.backend.services;

import flunav.events.DomainEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.*;

@Service
public class LiveSystemScheduler {
    private static final Logger logger = LoggerFactory.getLogger(LiveSystemScheduler.class);

    private final EventProcessor eventProcessor;
    private final TimeService timeService;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(100,
            Thread.ofVirtual().factory());

    private record ScheduledTask(ScheduledFuture<?> future, DomainEvent event) {
    }

    private final Map<String, ScheduledTask> scheduledTasksByItem = new ConcurrentHashMap<>();

    public LiveSystemScheduler(@Lazy EventProcessor eventProcessor, TimeService timeService) {
        this.eventProcessor = eventProcessor;
        this.timeService = timeService;
    }

    public void scheduleInternalEvent(DomainEvent event) {
        if (!(event instanceof flunav.events.EntityEvent ee)) {
            return;
        }

        String itemId = ee.getEntityId();
        cancelInternalEvent(itemId);

        Instant now = timeService.physicalNow();
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

            // Store both the future and the original event
            scheduledTasksByItem.put(itemId, new ScheduledTask(future, event));
        }
    }

    public void cancelInternalEvent(String itemId) {
        // Update cancellation to use the record
        ScheduledTask task = scheduledTasksByItem.remove(itemId);
        if (task != null && task.future() != null) {
            task.future().cancel(false); // cancel the actual thread
        }
    }

    public DomainEvent getScheduledEvent(String itemId) {
        ScheduledTask task = scheduledTasksByItem.get(itemId);
        return task != null ? task.event() : null;
    }
}
