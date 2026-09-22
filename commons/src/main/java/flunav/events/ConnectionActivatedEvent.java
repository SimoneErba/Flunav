package flunav.events;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Getter;

@Getter
public class ConnectionActivatedEvent extends EntityEvent {
    @JsonCreator
    public ConnectionActivatedEvent(
            @JsonProperty("connectionId") @JsonAlias("entityId") String connectionId,
            @JsonProperty("timestamp") Instant timestamp) {
        super(connectionId, "CONNECTION_ACTIVATED", timestamp);
    }

    public ConnectionActivatedEvent(String connectionId) {
        this(connectionId, null);
    }
}
