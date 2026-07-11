package flunav.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

import java.time.Instant;

@Getter
public class ItemExitedEvent extends EntityEvent {
    private final String locationId;

    @JsonCreator
    public ItemExitedEvent(
            @JsonProperty("entityId") String itemId,
            @JsonProperty("locationId") String locationId,
            @JsonProperty("timestamp") Instant timestamp) {
        super(itemId, "ITEM_EXITED", timestamp);
        this.locationId = locationId;
    }
}
