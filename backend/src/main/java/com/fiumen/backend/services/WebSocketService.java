package com.fiumen.backend.services;

import com.fiumen.backend.context.DatabaseContextHolder;
import com.fiumen.backend.models.simulation.SimulationStatus;
import com.fiumen.backend.models.UpdateModel;
import com.fiumen.backend.models.input.ItemInput;
import com.fiumen.backend.models.input.LocationInput;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
public class WebSocketService {
    private final SimpMessagingTemplate messagingTemplate;
    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(WebSocketService.class);

    public WebSocketService(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    public void broadcastNodeUpdate(String nodeId, Object update) {
        sendToTopic("nodes/" + nodeId, update);
    }

    public void broadcastSimulationUpdate(String simulationId, SimulationStatus status) {
        Map<String, Object> message = new HashMap<>();
        message.put("status", status);
        messagingTemplate.convertAndSend("/topic/simulation-status/" + simulationId, message);
    }

    public void broadcastItemCreated(ItemInput item) {
        EntityMessage<ItemInput> payload = new EntityMessage<>(CrudOperation.CREATED, item);
        sendToTopic("items", payload);
    }

    public void broadcastItemSpeedChanged(String itemId, double speed) {
        Map<String, Object> payload = Map.of("speed", speed);
        sendToTopic("items/" + itemId, payload);
    }

    public void broadcastItemDeactivated(String itemId) {
        Map<String, Object> payload = Map.of("active", false);
        sendToTopic("items/" + itemId, payload);
    }

    public void broadcastItemActivated(String itemId) {
        Map<String, Object> payload = Map.of("active", true);
        sendToTopic("items/" + itemId, payload);
    }

    public void broadcastItemPropertiesUpdated(UpdateModel updateModel) {
        sendToTopic("items/" + updateModel.getId(), updateModel.getProperties());
    }

    public void broadcastItemDeleted(String itemId) {
        EntityMessage<String> payload = new EntityMessage<>(CrudOperation.DELETED, itemId);
        sendToTopic("items", payload);
    }

    public void broadcastLocationCreated(LocationInput location) {
        EntityMessage<LocationInput> payload = new EntityMessage<>(CrudOperation.CREATED, location);
        sendToTopic("locations", payload);
    }

    public void broadcastLocationUpdated(String locationId, Map<String, Object> updateData) {
        sendToTopic("locations/" + locationId, updateData);
    }

    public void broadcastLocationDeleted(String locationId) {
        EntityMessage<String> payload = new EntityMessage<>(CrudOperation.DELETED, locationId);
        sendToTopic("locations", payload);
    }

    public void broadcastConnectionCreated(String fromLocationId, String toLocationId) {
        ConnectionMessage payload = new ConnectionMessage(fromLocationId, toLocationId, CrudOperation.CREATED);
        sendToTopic("connections", payload);
    }

    public void broadcastConnectionDeleted(String sourceLocationId, String targetLocationId) {
        ConnectionMessage payload = new ConnectionMessage(sourceLocationId, targetLocationId, CrudOperation.DELETED);
        sendToTopic("connections", payload);
    }

    public void broadcastPositionUpdate(String itemId, String locationId) {
        PositionUpdate payload = new PositionUpdate(itemId, locationId, PositionStatus.UPDATED);
        sendToTopic("positions", payload);
    }

    public void broadcastPositionLost(String itemId) {
        PositionUpdate payload = new PositionUpdate(itemId, null, PositionStatus.LOST);
        sendToTopic("positions", payload);
    }

    /**
     * Centralized method to send a message. It checks for a simulation ID in the
     * context and constructs the appropriate topic string before broadcasting.
     *
     * @param subTopic The specific sub-topic for the message (e.g., "items", "positions").
     * @param payload  The object to be sent as the message body.
     */
    private void sendToTopic(String subTopic, Object payload) {
        String simulationId = DatabaseContextHolder.getSimulationId();
        String topic = (simulationId != null)
            ? String.format("/topic/simulations/%s/%s", simulationId, subTopic)
            : String.format("/topic/%s", subTopic);

        logger.debug("Broadcasting to {}: {}", topic, payload);
        messagingTemplate.convertAndSend(topic, payload);
    }

    private enum CrudOperation { CREATED, DELETED }
    private enum PositionStatus { UPDATED, LOST }

    private record PositionUpdate(String itemId, String locationId, PositionStatus status) {}
    private record EntityMessage<T>(CrudOperation operation, T data) {}
    private record ConnectionMessage(String from, String to, CrudOperation operation) {}
}