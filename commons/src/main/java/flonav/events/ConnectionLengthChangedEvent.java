package flonav.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Getter;

@Getter
public class ConnectionLengthChangedEvent extends EntityEvent {
    private final Double length;

    @JsonCreator
    public ConnectionLengthChangedEvent(@JsonProperty("connectionId") String connectionId,
            @JsonProperty("length") Double length) {
        super(connectionId, "CONNECTION_LENGTH_CHANGED");
        this.length = length;
    }
}