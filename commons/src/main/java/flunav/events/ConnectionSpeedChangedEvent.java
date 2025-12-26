package flunav.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Getter;

@Getter
public class ConnectionSpeedChangedEvent extends EntityEvent {
    private final Double speed;

    @JsonCreator
    public ConnectionSpeedChangedEvent(@JsonProperty("connectionId") String connectionId,
            @JsonProperty("speed") Double speed) {
        super(connectionId, "CONNECTION_SPEED_CHANGED");
        this.speed = speed;
    }
}