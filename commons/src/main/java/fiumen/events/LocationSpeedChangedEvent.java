package fiumen.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Getter;

@Getter
public class LocationSpeedChangedEvent extends EntityEvent {
    private final Double speed;

    @JsonCreator
    public LocationSpeedChangedEvent(@JsonProperty("locationId") String itemId, @JsonProperty("speed") Double speed) {
        super(itemId, "LOCATION_SPEED_CHANGED");
        this.speed = speed;
    }
} 