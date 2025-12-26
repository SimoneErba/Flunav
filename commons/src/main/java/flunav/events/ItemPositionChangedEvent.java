package flunav.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import flunav.types.PositionType;
import lombok.Getter;

@Getter
public class ItemPositionChangedEvent extends EntityEvent {
    private final String locationId;
    private final Double progress;

    @JsonCreator
    public ItemPositionChangedEvent(
            @JsonProperty("itemId") String itemId,
            @JsonProperty("locationId") String locationId,
            @JsonProperty("progress") Double progress) {

        super(itemId, "ITEM_POSITION_CHANGED");
        this.locationId = locationId;
        this.progress = progress;

    }
}