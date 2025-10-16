package com.flumen.backend.services;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import com.flumen.backend.context.DatabaseContextHolder;
import com.flumen.backend.models.simulation.SimulationStatus;

@Service
public class WebSocketService {
    private final SimpMessagingTemplate messagingTemplate;
    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(WebSocketService.class);

    public WebSocketService(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    public void broadcastNodeUpdate(String nodeId, Object update) {
        String simulationId = DatabaseContextHolder.getSimulationId();
        if (simulationId != null) {
            String topic = String.format("/topic/simulations/%s/nodes/" + nodeId, simulationId);
            messagingTemplate.convertAndSend(topic, update);
        }else{
            messagingTemplate.convertAndSend("/topic/nodes/" + nodeId, update);
        }
    }

    public void broadcastSimulationUpdate(String simulationId, SimulationStatus status) {
        messagingTemplate.convertAndSend("/simulation-status/"+ simulationId, status);
    }

    public void broadcastPositionUpdate(String itemId, String locationId) {
        String simulationId = DatabaseContextHolder.getSimulationId();
        PositionUpdate payload = new PositionUpdate(itemId, locationId, PositionStatus.UPDATED);

        if (simulationId != null) {
            String topic = String.format("/topic/simulations/%s/positions", simulationId);
            logger.debug("Broadcasting simulation position update to {}: {}", topic, payload);
            messagingTemplate.convertAndSend(topic, payload);
        } else {
            String topic = "/topic/positions";
            logger.info("Broadcasting live position update to {}: {}", topic, payload);
            messagingTemplate.convertAndSend(topic, payload);
        }
    }

    public void broadcastPositionLost(String itemId) {
        String simulationId = DatabaseContextHolder.getSimulationId();
        PositionUpdate payload = new PositionUpdate(itemId, null, PositionStatus.LOST);
        if (simulationId != null) {
            String topic = String.format("/topic/simulations/%s/positions", simulationId);
            messagingTemplate.convertAndSend(topic, payload);
        } else {
            String topic = "/topic/positions";
            messagingTemplate.convertAndSend(topic, payload);
        }
    }

    private enum PositionStatus {
        UPDATED,
        LOST
    }

    private record PositionUpdate(String itemId, String locationId, PositionStatus status) {}
}