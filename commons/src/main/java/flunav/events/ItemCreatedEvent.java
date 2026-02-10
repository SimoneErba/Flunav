package flunav.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import java.time.Instant;
import java.util.Map;

@Getter
public class ItemCreatedEvent extends EntityEvent {
    private final String name;
    private final Double speed;
    private final boolean active;
    private final String locationId;
    private final flunav.types.PositionType positionType;
    private final Double progress;

    private final Map<String, Object> properties;

    public ItemCreatedEvent(String itemId, String name, Double speed, boolean active, String locationId,
            flunav.types.PositionType positionType, Double progress, Map<String, Object> properties) {
        this(itemId, name, speed, active, locationId, positionType, progress, properties, null);
    }

    @JsonCreator
    public ItemCreatedEvent(
            @JsonProperty("entityId") String itemId,
            @JsonProperty("name") String name,
            @JsonProperty("speed") Double speed,
            @JsonProperty("active") boolean active,
            @JsonProperty("locationId") String locationId,
            @JsonProperty("positionType") flunav.types.PositionType positionType,
            @JsonProperty("progress") Double progress,
            @JsonProperty("properties") Map<String, Object> properties,
            @JsonProperty("timestamp") Instant timestamp) {
        super(itemId, "ITEM_CREATED", timestamp);
        this.name = name;
        this.speed = speed;
        this.active = active;
        this.locationId = locationId;
        this.positionType = positionType;
        this.properties = properties;
        this.progress = progress;
    }
}