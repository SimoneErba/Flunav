package fiumen.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import java.util.Map;

@Getter
public class ItemCreatedEvent extends EntityEvent {
    private final String name;
    private final Double speed;
    private final boolean active;
    private final String locationId;
    private final Double progress;

    private final Map<String, Object> properties;

    @JsonCreator
    public ItemCreatedEvent(
            @JsonProperty("entityId") String itemId,
            @JsonProperty("name") String name,
            @JsonProperty("speed") Double speed,
            @JsonProperty("active") boolean active,
            @JsonProperty("locationId") String locationId,
            @JsonProperty("progress") Double progress,
            @JsonProperty("properties") Map<String, Object> properties) {
        super(itemId, "ITEM_CREATED");
        this.name = name;
        this.speed = speed;
        this.active = active;
        this.locationId = locationId;
        this.properties = properties;
        this.progress = progress;
    }
}