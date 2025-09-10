package flumen.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Getter;

@Getter
public class ItemDeactivatedEvent extends EntityEvent {

    @JsonCreator
    public ItemDeactivatedEvent(@JsonProperty("itemId") String itemId) {
        super(itemId, "ITEM_DEACTIVATED");
    }
} 