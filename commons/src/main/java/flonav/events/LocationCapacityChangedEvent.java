package flonav.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Getter;

@Getter
public class LocationCapacityChangedEvent extends EntityEvent {
    private final Integer capacity;

    @JsonCreator
    public LocationCapacityChangedEvent(@JsonProperty("locationId") String itemId,
            @JsonProperty("capacity") Integer capacity) {
        super(itemId, "LOCATION_CAPACITY_CHANGED");
        this.capacity = capacity;
    }
}