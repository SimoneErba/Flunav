package flumen.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import java.util.Map;

@Getter
public class ItemCreatedEvent extends EntityEvent {
    private final String name;
    private final Double speed;
    private final boolean active;
    private final Map<String, Object> properties;

    @JsonCreator
    public ItemCreatedEvent(
        @JsonProperty("entityId") String itemId, // Mapped to base class field name in JSON
        @JsonProperty("name") String name,
        @JsonProperty("speed") Double speed,
        @JsonProperty("active") boolean active,
        @JsonProperty("properties") Map<String, Object> properties
    ) {
        super(itemId, "ITEM_CREATED");
        this.name = name;
        this.speed = speed;
        this.active = active;
        this.properties = properties;
    }
}