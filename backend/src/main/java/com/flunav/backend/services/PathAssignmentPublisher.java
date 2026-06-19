package com.flunav.backend.services;

import com.flunav.backend.context.DatabaseContextHolder;
import flunav.messages.ItemPathAssignmentMessage;
import flunav.types.RoutingStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AmqpTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class PathAssignmentPublisher {
    private static final Logger logger = LoggerFactory.getLogger(PathAssignmentPublisher.class);

    private final AmqpTemplate amqpTemplate;
    private final String pathAssignmentsQueue;
    private final boolean manageLogic;

    public PathAssignmentPublisher(
            AmqpTemplate amqpTemplate,
            @Value("${rabbitmq.queue.path-assignments}") String pathAssignmentsQueue,
            @Value("${simulation.manage-logic:true}") boolean manageLogic) {
        this.amqpTemplate = amqpTemplate;
        this.pathAssignmentsQueue = pathAssignmentsQueue;
        this.manageLogic = manageLogic;
    }

    /**
     * Emits managed route assignments as operational RabbitMQ messages only.
     * These messages describe the latest physical exit path and are intentionally
     * not domain events, so replay and simulation history remain unchanged.
     */
    public void publishIfAssigned(String itemId, String finalDestinationId, RoutingStatus routingStatus,
            List<String> path, Instant timestamp, boolean publishExternalSignal) {
        if (!publishExternalSignal || !manageLogic || DatabaseContextHolder.getSimulationId() != null) {
            return;
        }
        if (routingStatus != RoutingStatus.ASSIGNED
                || finalDestinationId == null
                || path == null
                || path.isEmpty()) {
            return;
        }

        try {
            amqpTemplate.convertAndSend(pathAssignmentsQueue,
                    new ItemPathAssignmentMessage(itemId, finalDestinationId, path, timestamp));
        } catch (Exception e) {
            logger.error("Failed to publish path assignment for item {}", itemId, e);
        }
    }
}
