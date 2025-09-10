package flumen.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Getter;

@Getter
public class ItemPositionChangedEvent extends EntityEvent {
    private final String locationId;

    @JsonCreator
    public ItemPositionChangedEvent(@JsonProperty("itemId") String itemId, @JsonProperty("locationId") String locationId) {
        super(itemId, "ITEM_POSITION_CHANGED");
        this.locationId = locationId;
    }
}
