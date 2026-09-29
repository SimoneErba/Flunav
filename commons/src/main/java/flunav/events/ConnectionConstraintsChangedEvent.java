package flunav.events;

import java.time.Instant;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

@Getter
public class ConnectionConstraintsChangedEvent extends EntityEvent {
    private final double minDistance;
    private final Integer capacity;

    @JsonCreator
    public ConnectionConstraintsChangedEvent(@JsonProperty("entityId") String id,
            @JsonProperty("minDistance") double minDistance, @JsonProperty("capacity") Integer capacity,
            @JsonProperty("timestamp") Instant timestamp) {
        super(id, "CONNECTION_CONSTRAINTS_CHANGED", timestamp);
        this.minDistance = minDistance;
        this.capacity = capacity;
    }
}
