package com.fiumen.backend.services;

import com.fiumen.backend.context.DatabaseContextHolder;
import com.fiumen.backend.models.UpdateModel;
import com.fiumen.backend.models.input.ItemInput;
import com.fiumen.backend.models.input.LocationInput;
import com.fiumen.backend.models.response.ConveyorResponse;
import com.fiumen.backend.models.simulation.SimulationStatus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.HashMap;

enum CrudOperation {
    CREATED, DELETED
}

enum PositionStatus {
    UPDATED, LOST
}

// --- DTO Records (Updated with Timestamp) ---

record PositionUpdate(String itemId, String edgeId, long timestamp, PositionStatus status) {
}

record ConnectionMessage(String from, String to, CrudOperation operation, ConveyorResponse data, long timestamp) {
}

record EntityMessage<T>(CrudOperation operation, T data, long timestamp) {
}

// New: Wrapper for property updates to include timestamp
record EntityUpdateMessage(String id, Map<String, Object> properties, long timestamp) {
}

@Service
public class WebSocketService {
    private final SimpMessagingTemplate messagingTemplate;
    private static final Logger logger = LoggerFactory.getLogger(WebSocketService.class);

    public WebSocketService(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    public void broadcastSimulationUpdate(String simulationId, SimulationStatus status) {
        Map<String, Object> message = new HashMap<>();
        message.put("status", status);
        messagingTemplate.convertAndSend("/topic/simulation-status/" + simulationId, message);
    }

    public void broadcastSpeedUpdate(String simulationId, double speedFactor) {
        Map<String, Object> message = new HashMap<>();
        message.put("speed", speedFactor);
        messagingTemplate.convertAndSend("/topic/simulation-speed/" + simulationId, message);
    }

    // --- ITEM EVENTS ---

    public void broadcastItemCreated(ItemInput item, Instant timestamp) {
        EntityMessage<ItemInput> payload = new EntityMessage<>(
                CrudOperation.CREATED,
                item,
                timestamp.toEpochMilli());
        sendToTopic("items", payload);
    }

    public void broadcastItemDeleted(String itemId, Instant timestamp) {
        EntityMessage<String> payload = new EntityMessage<>(
                CrudOperation.DELETED,
                itemId,
                timestamp.toEpochMilli());
        sendToTopic("items", payload);
    }

    public void broadcastItemUpdated(UpdateModel updateModel, Instant timestamp) {
        EntityUpdateMessage payload = new EntityUpdateMessage(
                updateModel.getId(),
                updateModel.getProperties(),
                timestamp.toEpochMilli());
        sendToTopic("items/updates", payload);
    }

    // --- LOCATION EVENTS (Nodes) ---

    public void broadcastLocationCreated(LocationInput location, Instant timestamp) {
        EntityMessage<LocationInput> payload = new EntityMessage<>(
                CrudOperation.CREATED,
                location,
                timestamp.toEpochMilli());
        sendToTopic("locations", payload);
    }

    public void broadcastLocationDeleted(String locationId, Instant timestamp) {
        EntityMessage<String> payload = new EntityMessage<>(
                CrudOperation.DELETED,
                locationId,
                timestamp.toEpochMilli());
        sendToTopic("locations", payload);
    }

    public void broadcastLocationPropertiesUpdated(UpdateModel updateModel, Instant timestamp) {
        EntityUpdateMessage payload = new EntityUpdateMessage(
                updateModel.getId(),
                updateModel.getProperties(),
                timestamp.toEpochMilli());
        sendToTopic("locations/updates", payload);
    }

    // --- CONNECTION EVENTS (Conveyors/Edges) ---

    public void broadcastConnectionCreated(ConveyorResponse conveyor, Instant timestamp) {
        ConnectionMessage payload = new ConnectionMessage(
                conveyor.getSourceId(),
                conveyor.getTargetId(),
                CrudOperation.CREATED,
                conveyor,
                timestamp.toEpochMilli());
        sendToTopic("connections", payload);
    }

    public void broadcastConnectionDeleted(String sourceLocationId, String targetLocationId, Instant timestamp) {
        ConnectionMessage payload = new ConnectionMessage(
                sourceLocationId,
                targetLocationId,
                CrudOperation.DELETED,
                null,
                timestamp.toEpochMilli());
        sendToTopic("connections", payload);
    }

    public void broadcastConnectionUpdated(UpdateModel updateModel, Instant timestamp) {
        EntityUpdateMessage payload = new EntityUpdateMessage(
                updateModel.getId(),
                updateModel.getProperties(),
                timestamp.toEpochMilli());
        sendToTopic("connections/updates", payload);
    }

    // --- POSITION EVENTS (The Physics) ---

    public void broadcastPositionUpdate(String itemId, String edgeId, Instant timestamp) {
        PositionUpdate payload = new PositionUpdate(
                itemId,
                edgeId,
                timestamp.toEpochMilli(),
                PositionStatus.UPDATED);
        sendToTopic("positions", payload);
    }

    public void broadcastPositionLost(String itemId, Instant timestamp) {
        PositionUpdate payload = new PositionUpdate(
                itemId,
                null,
                timestamp.toEpochMilli(),
                PositionStatus.LOST);
        sendToTopic("positions", payload);
    }

    // --- HELPER ---

    private void sendToTopic(String subTopic, Object payload) {
        String simulationId = DatabaseContextHolder.getSimulationId();
        String topic = (simulationId != null)
                ? String.format("/topic/simulations/%s/%s", simulationId, subTopic)
                : String.format("/topic/%s", subTopic);

        // logger.debug("Broadcasting to {}: {}", topic, payload);
        messagingTemplate.convertAndSend(topic, payload);
    }
}