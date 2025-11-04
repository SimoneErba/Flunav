package fiumen.events;

import lombok.Getter;
import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@Getter
@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME, 
    include = JsonTypeInfo.As.PROPERTY, 
    property = "eventType",
    defaultImpl = UnknownEvent.class
)
@JsonSubTypes({
    @JsonSubTypes.Type(value = ItemActivatedEvent.class, name = "ITEM_ACTIVATED"),
    @JsonSubTypes.Type(value = ItemCreatedEvent.class, name = "ITEM_CREATED"),
    @JsonSubTypes.Type(value = ItemDeactivatedEvent.class, name = "ITEM_DEACTIVATED"),
    @JsonSubTypes.Type(value = ItemPositionChangedEvent.class, name = "ITEM_POSITION_CHANGED"),
    @JsonSubTypes.Type(value = ItemPositionCreatedEvent.class, name = "ITEM_POSITION_CREATED"),
    @JsonSubTypes.Type(value = ItemPositionDeletedEvent.class, name = "ITEM_POSITION_DELETED"),

    @JsonSubTypes.Type(value = ItemPropertiesUpdatedEvent.class, name = "ITEM_PROPERTIES_UPDATED"),
    @JsonSubTypes.Type(value = ItemSpeedChangedEvent.class, name = "ITEM_SPEED_CHANGED"),
    @JsonSubTypes.Type(value = ItemDeletedEvent.class, name = "ITEM_DELETED"),

    @JsonSubTypes.Type(value = LocationActivatedEvent.class, name = "LOCATION_ACTIVATED"),
    @JsonSubTypes.Type(value = LocationConnectionCreatedEvent.class, name = "LOCATION_CONNECTION_CREATED"),
    @JsonSubTypes.Type(value = LocationCreatedEvent.class, name = "LOCATION_CREATED"),
    @JsonSubTypes.Type(value = LocationDeactivatedEvent.class, name = "LOCATION_DEACTIVATED"),
    @JsonSubTypes.Type(value = LocationDeletedEvent.class, name = "LOCATION_DELETED"),
    @JsonSubTypes.Type(value = LocationPropertiesUpdatedEvent.class, name = "LOCATION_PROPERTIES_UPDATED"),

    @JsonSubTypes.Type(value = ConnectionDeletedEvent.class, name = "CONNECTION_DELETED"),

})
public abstract class DomainEvent {
    private final String eventId;
    private final Instant timestamp;
    private final String eventType;

    protected DomainEvent(String eventType) {
        this.eventId = UUID.randomUUID().toString();
        this.timestamp = Instant.now();
        this.eventType = eventType;
    }
} 