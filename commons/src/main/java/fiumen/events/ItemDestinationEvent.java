package fiumen.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Getter;

@Getter
public class ItemDestinationEvent extends EntityEvent {
    private final String locationId;

    @JsonCreator
    public ItemDestinationEvent(@JsonProperty("itemId") String itemId, @JsonProperty("locationId") String locationId) {
        super(itemId, "ITEM_DESTINATION");
        this.locationId = locationId;
    }
}
