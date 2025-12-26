package flunav.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Getter;

@Getter
public class ItemSpeedChangedEvent extends EntityEvent {
    private final Double speed;

    @JsonCreator
    public ItemSpeedChangedEvent(@JsonProperty("itemId") String itemId, @JsonProperty("speed") Double speed) {
        super(itemId, "ITEM_SPEED_CHANGED");
        this.speed = speed;
    }
}