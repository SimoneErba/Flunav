package fiumen.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Getter;

@Getter
public class LocationCoordinatesChangedEvent extends EntityEvent {
    private final Double longitude;
    private final Double latitude;

    @JsonCreator
    public LocationCoordinatesChangedEvent(@JsonProperty("locationId") String itemId,
            @JsonProperty("latitude") Double latitude, @JsonProperty("longitude") Double longitude) {
        super(itemId, "LOCATION_COORDINATES_CHANGED");
        this.longitude = longitude;
        this.latitude = latitude;
    }
}