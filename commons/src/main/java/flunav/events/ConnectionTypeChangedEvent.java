package flunav.events;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import flunav.types.ConveyorType;
import lombok.Getter;

@Getter
public class ConnectionTypeChangedEvent extends EntityEvent {
    private final ConveyorType conveyorType;

    @JsonCreator
    public ConnectionTypeChangedEvent(
            @JsonProperty("entityId") String connectionId,
            @JsonProperty("conveyorType") ConveyorType conveyorType,
            @JsonProperty("timestamp") Instant timestamp) {
        super(connectionId, "CONNECTION_TYPE_CHANGED", timestamp);
        this.conveyorType = conveyorType;
    }

    public ConnectionTypeChangedEvent(String connectionId, ConveyorType conveyorType) {
        this(connectionId, conveyorType, null);
    }
}
