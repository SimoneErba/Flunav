package com.flunav.backend.messaging;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.amqp.core.Message;

import com.rabbitmq.client.Channel;

import flunav.events.DomainEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flunav.backend.services.EventProcessor;

@Component
public class ItemEventListener {
    private static final Logger logger = LoggerFactory.getLogger(ItemEventListener.class);
    private final EventProcessor eventProcessor;
    private final ObjectMapper objectMapper;

    public ItemEventListener(EventProcessor eventProcessor, ObjectMapper objectMapper) {
        this.eventProcessor = eventProcessor;
        this.objectMapper = objectMapper;
    }

    /**
     * Acknowledges a delivery only after the event reducer completes successfully.
     * Permanent deserialization or processing failures are rejected without requeue
     * so RabbitMQ routes them to the logging DLQ configured on the source queue.
     */
    @RabbitListener(
            id = "itemEventListener",
            queues = "${rabbitmq.queue.item-events}",
            containerFactory = "manualAckRabbitListenerContainerFactory")
    public void handleEvent(Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        String jsonBody = new String(message.getBody(), StandardCharsets.UTF_8);
        try {
            DomainEvent event = objectMapper.readValue(jsonBody, DomainEvent.class);
            logger.info("Processing RabbitMQ event: {}", event.getEventType());
            eventProcessor.process(event, true).join();
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            logger.error("Rejecting failed RabbitMQ event to the DLQ. Message body: {}", jsonBody, e);
            channel.basicReject(deliveryTag, false);
        }
    }

    /**
     * Consumes dead-lettered events for operational logging. The DLQ is intentionally
     * a logging sink for now; durable retry or operator replay can be added later.
     */
    @RabbitListener(
            id = "itemEventDeadLetterLogger",
            queues = "${rabbitmq.queue.item-events-dlq}",
            autoStartup = "${rabbitmq.dlq-logger.enabled:false}")
    public void logDeadLetter(Message message) {
        String jsonBody = new String(message.getBody(), StandardCharsets.UTF_8);
        logger.error("RabbitMQ item event moved to DLQ. Message body: {}", jsonBody);
    }
}
