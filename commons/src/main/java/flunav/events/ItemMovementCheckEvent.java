package flunav.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import lombok.Getter;

/** Checks physical admission when an item reaches a conveyor boundary. */
@Getter
public class ItemMovementCheckEvent extends EntityEvent {
    private final String conveyorId;

    @JsonCreator
    public ItemMovementCheckEvent(@JsonProperty("entityId") String itemId,
            @JsonProperty("conveyorId") String conveyorId,
            @JsonProperty("timestamp") Instant timestamp) {
        super(itemId, "ITEM_MOVEMENT_CHECK", timestamp);
        this.conveyorId = conveyorId;
    }
}
