package flunav.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Getter;

@Getter
public class ItemRenamedEvent extends EntityEvent {
    private final String newName;

    @JsonCreator
    public ItemRenamedEvent(@JsonProperty("itemId") String itemId, String newName) {
        super(itemId, "ITEM_ACTIVATED");
        this.newName = newName;
    }
}