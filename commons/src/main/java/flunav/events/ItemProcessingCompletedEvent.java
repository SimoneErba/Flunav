package flunav.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

import java.time.Instant;

@Getter
public class ItemProcessingCompletedEvent extends EntityEvent {
    private final String locationId;

    @JsonCreator
    public ItemProcessingCompletedEvent(
            @JsonProperty("entityId") String itemId,
            @JsonProperty("locationId") String locationId,
            @JsonProperty("timestamp") Instant timestamp) {
        super(itemId, "ITEM_PROCESSING_COMPLETED", timestamp);
        this.locationId = locationId;
    }
}
