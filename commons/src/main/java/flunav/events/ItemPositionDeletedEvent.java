package flunav.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Getter;

@Getter
public class ItemPositionDeletedEvent extends EntityEvent {
    @JsonCreator
    public ItemPositionDeletedEvent(@JsonProperty("itemId") String itemId) {
        super(itemId, "ITEM_POSITION_DELETED");
    }
}
