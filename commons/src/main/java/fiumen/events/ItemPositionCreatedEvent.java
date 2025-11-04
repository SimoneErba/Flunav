package fiumen.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Getter;

@Getter
public class ItemPositionCreatedEvent extends EntityEvent {
    private final String locationId;

    @JsonCreator
    public ItemPositionCreatedEvent(@JsonProperty("itemId") String itemId, @JsonProperty("locationId") String locationId) {
        super(itemId, "ITEM_POSITION_CREATED");
        this.locationId = locationId;
    }
}
