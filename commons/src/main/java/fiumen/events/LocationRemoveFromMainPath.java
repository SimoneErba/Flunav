package fiumen.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Getter;

@Getter
public class LocationRemoveFromMainPath extends EntityEvent {

    @JsonCreator
    public LocationRemoveFromMainPath(@JsonProperty("locationId") String locationId) {
        super(locationId, "LOCATION_REMOVE_MAIN_PATH");
    }
}