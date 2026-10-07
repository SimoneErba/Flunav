package com.flunav.backend.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.amqp.autoconfigure.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    @Value("${rabbitmq.queue.item-events}")
    private String itemEventsQueue;

    @Value("${rabbitmq.queue.item-events-dlq}")
    private String itemEventsDeadLetterQueue;

    @Value("${rabbitmq.queue.commands}")
    private String commandsQueue;

    @Value("${rabbitmq.queue.path-assignments}")
    private String pathAssignmentsQueue;

    @Value("${rabbitmq.exchange.item-events}")
    private String itemEventsExchange;

    @Value("${rabbitmq.routing-key.item-events}")
    private String itemEventsRoutingKey;

    @Bean
    public Queue itemEventsQueue() {
        return QueueBuilder.durable(itemEventsQueue)
                .deadLetterExchange("")
                .deadLetterRoutingKey(itemEventsDeadLetterQueue)
                .build();
    }

    @Bean
    public Queue itemEventsDeadLetterQueue() {
        return QueueBuilder.durable(itemEventsDeadLetterQueue).build();
    }

    @Bean
    public Queue commandsQueue() {
        return new Queue(commandsQueue, true);
    }

    @Bean
    public Queue pathAssignmentsQueue() {
        return new Queue(pathAssignmentsQueue, true);
    }

    @Bean
    public CustomExchange itemEventsExchange() {
        // This is the classic way to create a custom exchange, which works in older
        // Spring AMQP versions.
        // The constructor takes: name, type, durable, autoDelete, arguments
        return new CustomExchange(itemEventsExchange, "x-consistent-hash", true, false);
    }

    @Bean
    public Binding itemEventsBinding(Queue itemEventsQueue, CustomExchange itemEventsExchange) {
        // For this exchange type, the routing key is what gets hashed.
        // It is NOT a wildcard like "#".
        return BindingBuilder.bind(itemEventsQueue)
                .to(itemEventsExchange)
                .with(itemEventsRoutingKey) // Use your specific routing key
                .noargs();
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    /**
     * Keeps Rabbit deliveries unacknowledged until the listener confirms that the
     * complete domain reduction succeeded. Rejected messages are dead-lettered by
     * the source queue rather than requeued into a poison-message loop.
     */
    @Bean(name = "manualAckRabbitListenerContainerFactory")
    public SimpleRabbitListenerContainerFactory manualAckRabbitListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        factory.setDefaultRequeueRejected(false);
        return factory;
    }

    @Bean
    public AmqpTemplate amqpTemplate(ConnectionFactory connectionFactory) {
        final RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory);
        rabbitTemplate.setMessageConverter(jsonMessageConverter());
        return rabbitTemplate;
    }
}
