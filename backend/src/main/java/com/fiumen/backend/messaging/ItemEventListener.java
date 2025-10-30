package com.fiumen.backend.messaging;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.amqp.core.Message;

import fiumen.events.DomainEvent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fiumen.backend.services.EventProcessor;

@Component
public class ItemEventListener {
    private static final Logger logger = LoggerFactory.getLogger(ItemEventListener.class);
    private final EventProcessor eventProcessor;
    private final ObjectMapper objectMapper;

    private final int numberOfWorkers;
    private final ExecutorService[] workers;

    public ItemEventListener(EventProcessor eventProcessor, ObjectMapper objectMapper) {
        this.eventProcessor = eventProcessor;
        this.objectMapper = objectMapper;
        this.numberOfWorkers = Runtime.getRuntime().availableProcessors();
        this.workers = new ExecutorService[numberOfWorkers];
        for (int i = 0; i < numberOfWorkers; i++) {
            workers[i] = Executors.newSingleThreadExecutor();
        }
        logger.info("Initialized ItemEventListener with {} sequential worker threads.", numberOfWorkers);
    }

    @RabbitListener(queues = "${rabbitmq.queue.item-events}")
    public void handleEvent(Message message) {
        String jsonBody = new String(message.getBody());
        try {
            JsonNode rootNode = objectMapper.readTree(jsonBody);
            String entityId = rootNode.path("entityId").asText(null);

            if (entityId == null) {
                workers[0].submit(() -> process(jsonBody));
                return;
            }

            // This hash calculation consistently maps an entityId to the same worker lane.
            int workerIndex = Math.abs(entityId.hashCode() % numberOfWorkers);
            workers[workerIndex].submit(() -> process(jsonBody));

        } catch (Exception e) {
            logger.error("Failed to dispatch event from RabbitMQ. Message body: {}", jsonBody, e);
        }
    }

    private void process(String jsonBody) {
        try {
            DomainEvent event = objectMapper.readValue(jsonBody, DomainEvent.class);
            logger.info("Worker processing event: {}", event.getEventType());
            eventProcessor.processEvent(event);
        } catch (Exception e) {
            logger.error("Worker failed to process event. Message body: {}", jsonBody, e);
        }
    }

    /**
     * This method is automatically called by Spring during application shutdown.
     * It ensures that the manually created worker threads are shut down gracefully.
     */
    @PreDestroy
    public void shutdown() {
        logger.info("Shutting down event listener worker threads...");
        for (int i = 0; i < numberOfWorkers; i++) {
            ExecutorService worker = workers[i];
            worker.shutdown();
            try {
                if (!worker.awaitTermination(5, TimeUnit.SECONDS)) {
                    logger.warn("Worker lane {} did not terminate in 5 seconds. Forcing shutdown.", i);
                    worker.shutdownNow();
                }
            } catch (InterruptedException e) {
                logger.error("Shutdown was interrupted. Forcing worker lane {} to stop.", i, e);
                worker.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        logger.info("All event listener worker threads have been shut down.");
    }
}