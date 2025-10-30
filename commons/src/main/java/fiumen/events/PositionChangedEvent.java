package fiumen.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Getter;

@Getter
public class PositionChangedEvent extends DomainEvent {
    private final String itemId;
    private final String locationId;
    
    @JsonCreator
    public PositionChangedEvent(
        @JsonProperty("itemId") String itemId,
        @JsonProperty("locationId") String locationId
    ) {
        super("POSITION_CHANGED");
        this.itemId = itemId;
        this.locationId = locationId;
    }
}