package flunav.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Getter;

@Getter
public class ItemDestinationEvent extends EntityEvent {
    private final String locationId;

    @JsonCreator
    public ItemDestinationEvent(@JsonProperty("entityId") String itemId, @JsonProperty("locationId") String locationId) {
        this(itemId, locationId, null);
    }

    public ItemDestinationEvent(String itemId, String locationId, java.time.Instant timestamp) {
        super(itemId, "ITEM_DESTINATION", timestamp);
        this.locationId = locationId;
    }
}
