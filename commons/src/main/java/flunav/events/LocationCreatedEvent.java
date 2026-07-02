package flunav.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import flunav.types.LocationType;
import lombok.Getter;

import java.util.Map;

@Getter
public class LocationCreatedEvent extends EntityEvent {

    private final String name;
    private final Boolean active;
    private final Double latitude;
    private final Double longitude;
    private final LocationType type;
    private final Integer capacity;
    private final Map<String, Object> properties;
    private final Long timeToProcessMs;

    /**
     * Constructor annotated for Jackson deserialization.
     */
    public LocationCreatedEvent(
            @JsonProperty("entityId") String locationId,
            @JsonProperty("name") String name,
            @JsonProperty("active") Boolean active,
            @JsonProperty("latitude") Double latitude,
            @JsonProperty("longitude") Double longitude,
            @JsonProperty("type") LocationType type,
            @JsonProperty("capacity") Integer capacity,
            @JsonProperty("properties") Map<String, Object> properties) {
        this(locationId, name, active, latitude, longitude, type, capacity, properties, null);
    }

    @JsonCreator
    public LocationCreatedEvent(
            @JsonProperty("entityId") String locationId,
            @JsonProperty("name") String name,
            @JsonProperty("active") Boolean active,
            @JsonProperty("latitude") Double latitude,
            @JsonProperty("longitude") Double longitude,
            @JsonProperty("type") LocationType type,
            @JsonProperty("capacity") Integer capacity,
            @JsonProperty("properties") Map<String, Object> properties,
            @JsonProperty("timeToProcessMs") Long timeToProcessMs) {
        super(locationId, "LOCATION_CREATED");
        this.name = name;
        this.active = active;
        this.properties = properties;
        this.latitude = latitude;
        this.longitude = longitude;
        this.type = type;
        this.capacity = capacity;
        this.timeToProcessMs = timeToProcessMs;
    }
}
