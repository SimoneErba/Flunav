package com.flunav.backend.services;

import com.flunav.backend.context.DatabaseContextHolder;
import com.flunav.backend.models.UpdateModel;
import com.flunav.backend.models.input.ItemInput;
import com.flunav.backend.models.input.LocationInput;
import com.flunav.backend.models.multisimulation.MultiSimulation;
import com.flunav.backend.models.response.ConveyorResponse;
import com.flunav.backend.models.response.ItemResponse;
import com.flunav.backend.models.response.MultiSimulationResponse;
import com.flunav.backend.models.response.ThroughputMetric;
import com.flunav.backend.models.simulation.SimulationStatus;
import com.flunav.backend.models.analytics.AnomalyNotification;

import flunav.context.UserContextHolder;
import flunav.types.PositionType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.HashMap;

enum CrudOperation {
    CREATED, DELETED, EMPTIED
}

enum PositionStatus {
    UPDATED, LOST
}

// --- DTO Records ---
record SocketEnvelope<T>(T payload, String senderId, long timestamp) {
}

record PositionUpdate(String itemId, String positionId, String edgeId, PositionStatus status, PositionType type,
        Double progress) {
}

record ConnectionMessage(String from, String to, CrudOperation operation, ConveyorResponse data) {
}

record EntityMessage<T>(CrudOperation operation, T data) {
}

record EntityUpdateMessage(String id, Map<String, Object> properties) {
}

@Service
public class WebSocketService {
    private final SimpMessagingTemplate messagingTemplate;

    public WebSocketService(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    // --- SIMULATION CONTROL EVENTS ---

    public void broadcastSimulationMode(com.flunav.backend.models.simulation.SimulationState state) {
        Map<String, Object> message = new HashMap<>();
        message.put("status", state.getStatus());
        message.put("kind", state.getKind());
        message.put("liveInputState", state.getLiveInputState());
        sendToTopic(state.getId(), "status", message,
                (state.getLastProcessedTimestamp() != null ? state.getLastProcessedTimestamp() : state.getTimestamp()).toEpochMilli());
    }

    public void broadcastSimulationUpdate(String simulationId, SimulationStatus status, Instant timestamp) {
        broadcastSimulationUpdate(simulationId, status, timestamp, null);
    }

    public void broadcastSimulationUpdate(String simulationId, SimulationStatus status, Instant timestamp,
            Double buildProgress) {
        Map<String, Object> message = new HashMap<>();
        message.put("status", status);
        if (buildProgress != null) {
            message.put("buildProgress", buildProgress);
        }
        // Pass explicit ID, subtopic "status"
        sendToTopic(simulationId, "status", message, timestamp.toEpochMilli());
    }

    public void broadcastSpeedUpdate(String simulationId, double speedFactor, Instant timestamp) {
        Map<String, Object> message = new HashMap<>();
        message.put("speed", speedFactor);
        // Pass explicit ID, subtopic "speed"
        sendToTopic(simulationId, "speed", message, timestamp.toEpochMilli());
    }

    public void broadcastMultiSimulationUpdate(MultiSimulation simulation, Instant timestamp) {
        sendToTopic(simulation.id(), "multi-simulation", MultiSimulationResponse.from(simulation),
                timestamp.toEpochMilli());
    }

    // --- ITEM EVENTS ---

    public void broadcastItemCreated(ItemResponse item, Instant timestamp) {
        EntityMessage<ItemResponse> payload = new EntityMessage<>(CrudOperation.CREATED, item);
        sendToTopic(null, "items", payload, timestamp.toEpochMilli());
    }

    public void broadcastItemDeleted(String itemId, Instant timestamp) {
        EntityMessage<String> payload = new EntityMessage<>(CrudOperation.DELETED, itemId);
        sendToTopic(null, "items", payload, timestamp.toEpochMilli());
    }

    public void broadcastItemUpdated(UpdateModel updateModel, Instant timestamp) {
        EntityUpdateMessage payload = new EntityUpdateMessage(updateModel.getId(), updateModel.getProperties());
        sendToTopic(null, "items/updates", payload, timestamp.toEpochMilli());
    }

    // --- LOCATION EVENTS ---

    public void broadcastLocationCreated(LocationInput location, Instant timestamp) {
        EntityMessage<LocationInput> payload = new EntityMessage<>(CrudOperation.CREATED, location);
        sendToTopic(null, "locations", payload, timestamp.toEpochMilli());
    }

    public void broadcastLocationDeleted(String locationId, Instant timestamp) {
        EntityMessage<String> payload = new EntityMessage<>(CrudOperation.DELETED, locationId);
        sendToTopic(null, "locations", payload, timestamp.toEpochMilli());
    }

    public void broadcastLocationPropertiesUpdated(UpdateModel updateModel, Instant timestamp) {
        EntityUpdateMessage payload = new EntityUpdateMessage(updateModel.getId(), updateModel.getProperties());
        sendToTopic(null, "locations/updates", payload, timestamp.toEpochMilli());
    }

    public void broadcastChuteEmptied(String chuteId, Instant timestamp) {
        sendToTopic(null, "locations/emptied", Map.of("chuteId", chuteId), timestamp.toEpochMilli());
    }

    // --- CONNECTION EVENTS ---

    public void broadcastConnectionCreated(ConveyorResponse conveyor, Instant timestamp) {
        ConnectionMessage payload = new ConnectionMessage(
                conveyor.getSourceId(),
                conveyor.getTargetId(),
                CrudOperation.CREATED,
                conveyor);
        sendToTopic(null, "connections", payload, timestamp.toEpochMilli());
    }

    public void broadcastConnectionDeleted(String sourceLocationId, String targetLocationId, Instant timestamp) {
        ConnectionMessage payload = new ConnectionMessage(
                sourceLocationId,
                targetLocationId,
                CrudOperation.DELETED,
                null);
        sendToTopic(null, "connections", payload, timestamp.toEpochMilli());
    }

    public void broadcastConnectionUpdated(UpdateModel updateModel, Instant timestamp) {
        EntityUpdateMessage payload = new EntityUpdateMessage(updateModel.getId(), updateModel.getProperties());
        sendToTopic(null, "connections/updates", payload, timestamp.toEpochMilli());
    }

    // --- POSITION EVENTS ---

    public void broadcastPositionUpdate(String itemId, String positionId, Instant timestamp, PositionType type,
            double progress) {
        PositionUpdate payload = new PositionUpdate(itemId, positionId, positionId, PositionStatus.UPDATED, type,
                progress);
        sendToTopic(null, "positions", payload, timestamp.toEpochMilli());
    }

    public void broadcastPositionLost(String itemId, Instant timestamp) {
        PositionUpdate payload = new PositionUpdate(itemId, null, null, PositionStatus.LOST, null, null);
        sendToTopic(null, "positions", payload, timestamp.toEpochMilli());
    }

    public void broadcastThroughputMetric(String simulationId, ThroughputMetric metric) {
        try (var ignored = UserContextHolder.enterSenderContext(null)) {
            sendToTopic(simulationId, "analytics/throughput", metric, metric.getTimestamp().toEpochMilli());
        }
    }

    public void broadcastAnomaly(String simulationId, AnomalyNotification notification) {
        try (var ignored = UserContextHolder.enterSenderContext(null)) {
            sendToTopic(simulationId, "analytics/anomalies", notification,
                    notification.virtualTimestamp().toEpochMilli());
        }
    }

    // --- HELPER ---

    /**
     * Centralized method to wrap messages in an Envelope and send them to the
     * correct topic.
     * 
     * @param explicitSimulationId If provided, forces the message to this
     *                             simulation ID.
     *                             If null, tries to get it from the Context.
     * @param subTopic             The specific channel (e.g., "items", "status").
     * @param payload              The business data.
     * @param timestamp            The simulation timestamp.
     */
    private void sendToTopic(String explicitSimulationId, String subTopic, Object payload, long timestamp) {
        // 1. Determine Simulation ID (Explicit > Context > Null/Live)
        String simulationId = (explicitSimulationId != null)
                ? explicitSimulationId
                : DatabaseContextHolder.getSimulationId();

        // 2. Construct Topic
        // Format: /topic/simulations/{id}/{subTopic} OR /topic/{subTopic}
        String topic = (simulationId != null)
                ? String.format("/topic/simulations/%s/%s", simulationId, subTopic)
                : String.format("/topic/%s", subTopic);

        // 3. Get Sender ID from Context
        String currentSenderId = UserContextHolder.getSenderId();

        // 4. Wrap in Envelope
        SocketEnvelope<Object> envelope = new SocketEnvelope<>(payload, currentSenderId, timestamp);

        // 5. Send
        // logger.debug("Broadcasting to {}: {}", topic, envelope);
        messagingTemplate.convertAndSend(topic, envelope);
    }
}
