package flunav.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Getter;

@Getter
public class ConnectionActivatedEvent extends EntityEvent {
    @JsonCreator
    public ConnectionActivatedEvent(@JsonProperty("connectionId") String connectionId) {
        super(connectionId, "CONNECTION_ACTIVATED");
    }
}