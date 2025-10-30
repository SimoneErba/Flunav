package fiumen.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Getter;

@Getter
public class LocationActivatedEvent extends EntityEvent {

    @JsonCreator
    public LocationActivatedEvent(@JsonProperty("locationId") String locationId) {
        super(locationId, "LOCATION_ACTIVATED");
    }
} 