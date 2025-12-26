package flunav.events;

public abstract class EntityEvent extends DomainEvent {
    private final String entityId;

    protected EntityEvent(String entityId, String eventType) {
        super(eventType);
        this.entityId = entityId;
    }

    public String getEntityId() {
        return entityId;
    }
}