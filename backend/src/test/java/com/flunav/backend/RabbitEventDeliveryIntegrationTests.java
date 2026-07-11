package com.flunav.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flunav.backend.services.ClickHouseService;

import flunav.events.ItemCreatedEvent;
import flunav.types.PositionType;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.listener.AbstractMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestConstructor;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
        "springwolf.enabled=false",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "state-recovery.enabled=false",
        "graph-snapshot.enabled=false",
        "stale-item-cleanup.enabled=false"
})
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class RabbitEventDeliveryIntegrationTests extends BaseIntegrationTest {

    private static final String ITEM_QUEUE = "item-events-queue";
    private static final String DLQ = "item-events-dlq";
    private static final String EXCHANGE = "item-events-exchange";

    private final RabbitTemplate rabbitTemplate;
    private final AmqpAdmin amqpAdmin;
    private final RabbitListenerEndpointRegistry listenerRegistry;
    private final ObjectMapper objectMapper;
    private final ClickHouseService clickHouseService;

    RabbitEventDeliveryIntegrationTests(
            RabbitTemplate rabbitTemplate,
            AmqpAdmin amqpAdmin,
            RabbitListenerEndpointRegistry listenerRegistry,
            ObjectMapper objectMapper,
            ClickHouseService clickHouseService) {
        this.rabbitTemplate = rabbitTemplate;
        this.amqpAdmin = amqpAdmin;
        this.listenerRegistry = listenerRegistry;
        this.objectMapper = objectMapper;
        this.clickHouseService = clickHouseService;
    }

    @BeforeEach
    void drainQueues() {
        stopItemListener();
        amqpAdmin.purgeQueue(ITEM_QUEUE, false);
        amqpAdmin.purgeQueue(DLQ, false);
    }

    @AfterEach
    void cleanup() {
        stopItemListener();
        amqpAdmin.purgeQueue(ITEM_QUEUE, false);
        amqpAdmin.purgeQueue(DLQ, false);
    }

    @Test
    void failedReductionIsRejectedToDeadLetterQueue() throws Exception {
        AbstractMessageListenerContainer listener = assertInstanceOf(
                AbstractMessageListenerContainer.class,
                listenerRegistry.getListenerContainer("itemEventListener"));
        assertEquals(AcknowledgeMode.MANUAL, listener.getAcknowledgeMode());
        listener.start();

        ItemCreatedEvent invalidEvent = new ItemCreatedEvent(
                "rabbit-invalid-priority", "Invalid", 1.0, 2.0, true,
                "missing-location", PositionType.LOCATION, 0.0, null, Map.of(), Instant.now());
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        rabbitTemplate.send(EXCHANGE, invalidEvent.getEntityId(),
                new Message(objectMapper.writeValueAsBytes(invalidEvent), properties));

        Message deadLetter = awaitMessage(DLQ, 5_000L);
        assertNotNull(deadLetter, "Failed reducer delivery should be dead-lettered");
        assertNotNull(deadLetter.getMessageProperties().getHeaders().get("x-death"));
        clickHouseService.flushAllEventsOrThrow();
        assertTrue(clickHouseService.getEventsBetween(
                invalidEvent.getTimestamp().minusSeconds(1), invalidEvent.getTimestamp().plusSeconds(1)).stream()
                .noneMatch(event -> event instanceof flunav.events.EntityEvent entityEvent
                        && invalidEvent.getEntityId().equals(entityEvent.getEntityId())));
    }

    private Message awaitMessage(String queue, long timeoutMillis) {
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        Message message;
        do {
            message = rabbitTemplate.receive(queue, 100L);
        } while (message == null && System.nanoTime() < deadline);
        return message;
    }

    private void stopItemListener() {
        var listener = listenerRegistry.getListenerContainer("itemEventListener");
        if (listener != null && listener.isRunning()) {
            listener.stop();
        }
    }
}
