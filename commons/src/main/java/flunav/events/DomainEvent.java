package flunav.events;

import flunav.context.UserContextHolder;
import lombok.Getter;
import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@Getter
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "eventType", defaultImpl = UnknownEvent.class)
@JsonSubTypes({
        // --- ITEM EVENTS ---
        @JsonSubTypes.Type(value = ItemActivatedEvent.class, name = "ITEM_ACTIVATED"),
        @JsonSubTypes.Type(value = ItemCreatedEvent.class, name = "ITEM_CREATED"),
        @JsonSubTypes.Type(value = ItemDeactivatedEvent.class, name = "ITEM_DEACTIVATED"),
        @JsonSubTypes.Type(value = ItemDeletedEvent.class, name = "ITEM_DELETED"),
        @JsonSubTypes.Type(value = ItemDestinationEvent.class, name = "ITEM_DESTINATION"),
        @JsonSubTypes.Type(value = ItemPositionChangedEvent.class, name = "ITEM_POSITION_CHANGED"),
        @JsonSubTypes.Type(value = ItemPositionDeletedEvent.class, name = "ITEM_POSITION_DELETED"),
        @JsonSubTypes.Type(value = ItemPropertiesUpdatedEvent.class, name = "ITEM_PROPERTIES_UPDATED"),
        @JsonSubTypes.Type(value = ItemRenamedEvent.class, name = "ITEM_RENAMED"),
        @JsonSubTypes.Type(value = ItemSpeedChangedEvent.class, name = "ITEM_SPEED_CHANGED"),

        // --- LOCATION EVENTS ---
        @JsonSubTypes.Type(value = LocationActivatedEvent.class, name = "LOCATION_ACTIVATED"),
        @JsonSubTypes.Type(value = LocationAddToMainPath.class, name = "LOCATION_ADD_TO_MAIN_PATH"),
        @JsonSubTypes.Type(value = LocationCapacityChangedEvent.class, name = "LOCATION_CAPACITY_CHANGED"),
        @JsonSubTypes.Type(value = LocationCoordinatesChangedEvent.class, name = "LOCATION_COORDINATES_CHANGED"),
        @JsonSubTypes.Type(value = LocationCreatedEvent.class, name = "LOCATION_CREATED"),
        @JsonSubTypes.Type(value = LocationDeactivatedEvent.class, name = "LOCATION_DEACTIVATED"),
        @JsonSubTypes.Type(value = LocationDeletedEvent.class, name = "LOCATION_DELETED"),
        @JsonSubTypes.Type(value = LocationPropertiesUpdatedEvent.class, name = "LOCATION_PROPERTIES_UPDATED"),
        @JsonSubTypes.Type(value = LocationTypeChangedEvent.class, name = "LOCATION_TYPE_CHANGED"),
        @JsonSubTypes.Type(value = ChuteEmptyEvent.class, name = "CHUTE_EMPTY"),

        // --- CONNECTION (CONVEYOR) EVENTS ---
        @JsonSubTypes.Type(value = ConnectionActivatedEvent.class, name = "CONNECTION_ACTIVATED"),
        @JsonSubTypes.Type(value = ConnectionCreatedEvent.class, name = "CONNECTION_CREATED"),
        @JsonSubTypes.Type(value = ConnectionDeactivatedEvent.class, name = "CONNECTION_DEACTIVATED"),
        @JsonSubTypes.Type(value = ConnectionDeletedEvent.class, name = "CONNECTION_DELETED"),
        @JsonSubTypes.Type(value = ConnectionLengthChangedEvent.class, name = "CONNECTION_LENGTH_CHANGED"),
        @JsonSubTypes.Type(value = ConnectionRemoveFromMainPath.class, name = "CONNECTION_REMOVE_FROM_MAIN_PATH"),
        @JsonSubTypes.Type(value = ConnectionSpeedChangedEvent.class, name = "CONNECTION_SPEED_CHANGED"),
        @JsonSubTypes.Type(value = ConnectionPropertiesUpdatedEvent.class, name = "CONNECTION_PROPERTIES_UPDATED"),
})
public abstract class DomainEvent {
    private final String eventId;
    private final Instant timestamp;
    private final String eventType;
    private final String senderId;

    protected DomainEvent(String eventType) {
        this(eventType, (Instant) null);
    }

    protected DomainEvent(String eventType, Instant timestamp) {
        this.eventId = UUID.randomUUID().toString();
        this.timestamp = timestamp != null ? timestamp : Instant.now();
        this.eventType = eventType;
        this.senderId = UserContextHolder.getSenderId();
    }

    // Constructor for manual senderId injection (useful for tests or internal
    // system events)
    protected DomainEvent(String eventType, String senderId) {
        this(eventType, senderId, null);
    }

    protected DomainEvent(String eventType, String senderId, Instant timestamp) {
        this.eventId = UUID.randomUUID().toString();
        this.timestamp = timestamp != null ? timestamp : Instant.now();
        this.eventType = eventType;
        this.senderId = senderId;
    }
}