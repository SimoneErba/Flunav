package flunav.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

@Getter
public class LocationProcessingTimeChangedEvent extends EntityEvent {
    private final Long timeToProcessMs;

    @JsonCreator
    public LocationProcessingTimeChangedEvent(
            @JsonProperty("locationId") String locationId,
            @JsonProperty("timeToProcessMs") Long timeToProcessMs) {
        super(locationId, "LOCATION_PROCESSING_TIME_CHANGED");
        this.timeToProcessMs = timeToProcessMs;
    }
}
