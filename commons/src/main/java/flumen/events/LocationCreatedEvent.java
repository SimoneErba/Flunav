package flumen.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

import java.util.Map;

@Getter
public class LocationCreatedEvent extends EntityEvent {

    private final String name;
    private final Boolean active;
    private final Double latitude;
    private final Double longitude;
    private final Double length;
    private final Double speed;
    private final String type;
    private final Map<String, Object> properties;

    /**
     * Constructor annotated for Jackson deserialization.
     */
    @JsonCreator
    public LocationCreatedEvent(
        @JsonProperty("entityId") String locationId,
        @JsonProperty("name") String name,
        @JsonProperty("active") Boolean active,
        @JsonProperty("latitude") Double latitude,
        @JsonProperty("longitude") Double longitude,
        @JsonProperty("length") Double length,
        @JsonProperty("speed") Double speed,
        @JsonProperty("type") String type,
        @JsonProperty("properties") Map<String, Object> properties
    ) {
        super(locationId, "LOCATION_CREATED");
        this.name = name;
        this.active = active;
        this.properties = properties;
        this.latitude = latitude;
        this.longitude = longitude;
        this.length = length;
        this.speed = speed;
        this.type = type;
    }
}