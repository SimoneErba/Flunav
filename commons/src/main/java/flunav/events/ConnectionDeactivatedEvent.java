package flunav.events;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Getter;

@Getter
public class ConnectionDeactivatedEvent extends EntityEvent {
    @JsonCreator
    public ConnectionDeactivatedEvent(
            @JsonProperty("connectionId") @JsonAlias("entityId") String connectionId,
            @JsonProperty("timestamp") Instant timestamp) {
        super(connectionId, "CONNECTION_DEACTIVATED", timestamp);
    }

    public ConnectionDeactivatedEvent(String connectionId) {
        this(connectionId, null);
    }
}
