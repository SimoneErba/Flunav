package flonav.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Getter;

@Getter
public class LocationAddToMainPath extends EntityEvent {

    @JsonCreator
    public LocationAddToMainPath(@JsonProperty("locationId") String locationId) {
        super(locationId, "LOCATION_ADD_MAIN_PATH");
    }
}