package com.flunav.backend.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    @Value("${rabbitmq.queue.item-events}")
    private String itemEventsQueue;

    @Value("${rabbitmq.exchange.item-events}")
    private String itemEventsExchange;

    @Value("${rabbitmq.routing-key.item-events}")
    private String itemEventsRoutingKey;

    @Bean
    public Queue itemEventsQueue() {
        return new Queue(itemEventsQueue, true);
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

    @Bean
    public AmqpTemplate amqpTemplate(ConnectionFactory connectionFactory) {
        final RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory);
        rabbitTemplate.setMessageConverter(jsonMessageConverter());
        return rabbitTemplate;
    }
}