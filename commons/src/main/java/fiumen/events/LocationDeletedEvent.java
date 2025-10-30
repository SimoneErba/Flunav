package fiumen.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Getter;

@Getter
public class LocationDeletedEvent extends EntityEvent {
    @JsonCreator
    public LocationDeletedEvent(@JsonProperty("locationId") String locationId) {
        super(locationId, "LOCATION_DELETED");
    }
} 