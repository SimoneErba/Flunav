package flunav.events;

import flunav.types.LocationType;
import lombok.Getter;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

@Getter
public class LocationTypeChangedEvent extends EntityEvent {
    private final LocationType locationType;

    @JsonCreator
    public LocationTypeChangedEvent(@JsonProperty("locationId") String locationId,
            @JsonProperty("locationType") LocationType locationType) {
        super(locationId, "LOCATION_TYPE_CHANGED");
        this.locationType = locationType;
    }
}
