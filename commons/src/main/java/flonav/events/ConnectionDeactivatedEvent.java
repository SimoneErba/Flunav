package flonav.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Getter;

@Getter
public class ConnectionDeactivatedEvent extends EntityEvent {
    @JsonCreator
    public ConnectionDeactivatedEvent(@JsonProperty("connectionId") String connectionId) {
        super(connectionId, "CONNECTION_DEACTIVATED");
    }
}