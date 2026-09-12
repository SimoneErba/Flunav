package flunav.events;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class ReleaseStagingConveyorEvent extends EntityEvent {

    @JsonCreator
    public ReleaseStagingConveyorEvent(
            @JsonProperty("entityId") String conveyorId,
            @JsonProperty("timestamp") Instant timestamp) {
        super(conveyorId, "RELEASE_STAGING_CONVEYOR", timestamp);
    }

    public ReleaseStagingConveyorEvent(String conveyorId) {
        this(conveyorId, null);
    }
}
