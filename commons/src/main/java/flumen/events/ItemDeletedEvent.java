package flumen.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Getter;

@Getter
public class ItemDeletedEvent extends EntityEvent {
    @JsonCreator
    public ItemDeletedEvent(@JsonProperty("itemId") String itemId) {
        super(itemId, "ITEM_DELETED");
    }
} 