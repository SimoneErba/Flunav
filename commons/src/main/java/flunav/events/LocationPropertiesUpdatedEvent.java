package flunav.events;

import lombok.Getter;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

@Getter
public class LocationPropertiesUpdatedEvent extends EntityEvent {
    private final Map<String, Object> updatedProperties;

    @JsonCreator
    public LocationPropertiesUpdatedEvent(@JsonProperty("locationId") String locationId,
            @JsonProperty("updatedProperties") Map<String, Object> updatedProperties) {
        super(locationId, "LOCATION_UPDATED");
        this.updatedProperties = updatedProperties;
    }
}