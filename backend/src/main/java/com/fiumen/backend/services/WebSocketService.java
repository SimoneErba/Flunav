package com.fiumen.backend.services;

import com.fiumen.backend.context.DatabaseContextHolder;
import com.fiumen.backend.models.UpdateModel;
import com.fiumen.backend.models.input.ItemInput;
import com.fiumen.backend.models.input.LocationInput;
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

// CHANGED: Added 'timestamp' and renamed locationId to edgeId for clarity
record PositionUpdate(String itemId, String edgeId, long timestamp, PositionStatus status) {
}

record ConnectionMessage(String from, String to, CrudOperation operation) {
}

record EntityMessage<T>(CrudOperation operation, T data) {
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

    // NEW: Broadcast global playback speed (e.g., 1.0x, 5.0x)
    public void broadcastSpeedUpdate(String simulationId, double speedFactor) {
        Map<String, Object> message = new HashMap<>();
        message.put("speed", speedFactor);
        messagingTemplate.convertAndSend("/topic/simulation-speed/" + simulationId, message);
    }

    // --- ITEM EVENTS ---

    public void broadcastItemCreated(ItemInput item) {
        EntityMessage<ItemInput> payload = new EntityMessage<>(CrudOperation.CREATED, item);
        sendToTopic("items", payload);
    }

    public void broadcastItemDeleted(String itemId) {
        EntityMessage<String> payload = new EntityMessage<>(CrudOperation.DELETED, itemId);
        sendToTopic("items", payload);
    }

    public void broadcastItemUpdated(UpdateModel updateModel) {
        sendToTopic("items/updates", updateModel);
    }

    // --- LOCATION EVENTS (Nodes) ---

    public void broadcastLocationCreated(LocationInput location) {
        EntityMessage<LocationInput> payload = new EntityMessage<>(CrudOperation.CREATED, location);
        sendToTopic("locations", payload);
    }

    public void broadcastLocationDeleted(String locationId) {
        EntityMessage<String> payload = new EntityMessage<>(CrudOperation.DELETED, locationId);
        sendToTopic("locations", payload);
    }

    public void broadcastLocationPropertiesUpdated(UpdateModel updateModel) {
        sendToTopic("locations/updates", updateModel);
    }

    // --- CONNECTION EVENTS (Conveyors/Edges) ---

    public void broadcastConnectionCreated(String fromLocationId, String toLocationId) {
        // Note: The frontend might need to fetch the full conveyor details after this,
        // or you could pass the full Conveyor object here instead of just IDs.
        ConnectionMessage payload = new ConnectionMessage(fromLocationId, toLocationId, CrudOperation.CREATED);
        sendToTopic("connections", payload);
    }

    public void broadcastConnectionDeleted(String sourceLocationId, String targetLocationId) {
        ConnectionMessage payload = new ConnectionMessage(sourceLocationId, targetLocationId, CrudOperation.DELETED);
        sendToTopic("connections", payload);
    }

    // NEW: Handle updates to Conveyors (Speed, Length, Active status)
    public void broadcastConnectionUpdated(UpdateModel updateModel) {
        sendToTopic("connections/updates", updateModel);
    }

    // --- POSITION EVENTS (The Physics) ---

    // CHANGED: Now accepts Timestamp
    public void broadcastPositionUpdate(String itemId, String edgeId, Instant timestamp) {
        PositionUpdate payload = new PositionUpdate(
                itemId,
                edgeId,
                timestamp.toEpochMilli(),
                PositionStatus.UPDATED);
        sendToTopic("positions", payload);
    }

    public void broadcastPositionLost(String itemId) {
        // Timestamp doesn't matter for LOST, but we pass 0 or now
        PositionUpdate payload = new PositionUpdate(
                itemId,
                null,
                Instant.now().toEpochMilli(),
                PositionStatus.LOST);
        sendToTopic("positions", payload);
    }

    // --- HELPER ---

    private void sendToTopic(String subTopic, Object payload) {
        String simulationId = DatabaseContextHolder.getSimulationId();
        String topic = (simulationId != null)
                ? String.format("/topic/simulations/%s/%s", simulationId, subTopic)
                : String.format("/topic/%s", subTopic);

        logger.debug("Broadcasting to {}: {}", topic, payload);
        messagingTemplate.convertAndSend(topic, payload);
    }
}