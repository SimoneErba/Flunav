package flumen.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Getter;

@Getter
public class PositionCreatedEvent extends DomainEvent {
    private final String itemId;
    private final String locationId;
    
    @JsonCreator
    public PositionCreatedEvent(
        @JsonProperty("itemId") String itemId,
        @JsonProperty("locationId") String locationId
    ) {
        super("POSITION_CREATED");
        this.itemId = itemId;
        this.locationId = locationId;
    }
}
