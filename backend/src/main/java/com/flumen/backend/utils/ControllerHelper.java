package com.flumen.backend.utils;

import com.flumen.backend.services.EventProcessor;
import flumen.events.DomainEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Component
public class ControllerHelper {

    private final EventProcessor eventProcessor;
    private static final Logger logger = LoggerFactory.getLogger(ControllerHelper.class);

    @Autowired
    public ControllerHelper(EventProcessor eventProcessor) {
        this.eventProcessor = eventProcessor;
    }

    /**
     * A shared method to process a domain event and log the success.
     *
     * @param event The domain event to process.
     * @return A CompletableFuture containing the result map from the event processor.
     */
    public CompletableFuture<Map<String, Object>> processAndLogEvent(DomainEvent event) {
        return eventProcessor.process(event, true)
            .thenApply(resultMap -> {

                logger.debug("Successfully processed event of type {}",
                    event.getEventType());
                return resultMap;
            });
    }
}