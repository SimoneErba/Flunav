package flunav.events;

import java.time.Instant;

public abstract class EntityEvent extends DomainEvent {
    private final String entityId;

    protected EntityEvent(String entityId, String eventType) {
        this(entityId, eventType, null);
    }

    protected EntityEvent(String entityId, String eventType, Instant timestamp) {
        super(eventType, timestamp);
        this.entityId = entityId;
    }

    protected EntityEvent(String entityId, String eventType, String senderId, Instant timestamp) {
        super(eventType, senderId, timestamp);
        this.entityId = entityId;
    }

    public String getEntityId() {
        return entityId;
    }
}