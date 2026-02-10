package flunav.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Getter;
import java.time.Instant;

@Getter
public class ConnectionSpeedChangedEvent extends EntityEvent {
    private final Double speed;

    @JsonCreator
    public ConnectionSpeedChangedEvent(@JsonProperty("entityId") String connectionId,
            @JsonProperty("speed") Double speed) {
        this(connectionId, speed, null);
    }

    public ConnectionSpeedChangedEvent(String connectionId, Double speed, Instant timestamp) {
        super(connectionId, "CONNECTION_SPEED_CHANGED", timestamp);
        this.speed = speed;
    }
}