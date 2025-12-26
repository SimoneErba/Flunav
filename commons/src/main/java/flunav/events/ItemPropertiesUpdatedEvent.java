package flunav.events;

import lombok.Getter;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

@Getter
public class ItemPropertiesUpdatedEvent extends EntityEvent {
    private final Map<String, Object> properties;

    @JsonCreator
    public ItemPropertiesUpdatedEvent(@JsonProperty("itemId") String itemId,
            @JsonProperty("properties") Map<String, Object> properties) {
        super(itemId, "ITEM_PROPERTIES_UPDATED");
        this.properties = properties;
    }
}