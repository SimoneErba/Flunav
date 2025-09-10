package com.flumen.backend.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import flumen.events.DomainEvent;
import com.flumen.backend.services.EventProcessor;

@Component
public class ItemEventListener {
    private static final Logger logger = LoggerFactory.getLogger(ItemEventListener.class);
    private final EventProcessor eventProcessor;

    public ItemEventListener(EventProcessor eventProcessor) {
        this.eventProcessor = eventProcessor;
    }

    @RabbitListener(queues = "${rabbitmq.queue.item-events}", id = "graph-state-events-listener")
    public void handleItemEvent(DomainEvent event) {
        logger.info("Received event from RabbitMQ: {}", 
            event.getEventType());
        
        eventProcessor.process(event, true)
            .exceptionally(throwable -> {
                logger.error("Failed to process event from RabbitMQ: {}", 
                    event.getEventId(), throwable);
                return null;
            });
    }
}