package com.flunav.backend.test;

import org.springframework.amqp.core.AmqpTemplate;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.lang.reflect.Proxy;

@TestConfiguration
public class NoOpAmqpTestConfig {

    @Bean
    public AmqpTemplate amqpTemplate() {
        return (AmqpTemplate) Proxy.newProxyInstance(
                AmqpTemplate.class.getClassLoader(),
                new Class<?>[] { AmqpTemplate.class },
                (proxy, method, args) -> {
                    if (boolean.class.equals(method.getReturnType())) {
                        return false;
                    }
                    return null;
                });
    }
}
