package fiumen.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Getter;

@Getter
public class LocationLengthChangedEvent extends EntityEvent {
    private final Double length;

    @JsonCreator
    public LocationLengthChangedEvent(@JsonProperty("locationId") String itemId, @JsonProperty("length") Double length) {
        super(itemId, "LOCATION_LENGTH_CHANGED");
        this.length = length;
    }
} 