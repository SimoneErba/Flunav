package flunav.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import flunav.types.PositionType;
import lombok.Getter;

import java.time.Instant;

@Getter
public class ItemPositionChangedEvent extends EntityEvent {
    private final String locationId;
    private final Double progress;
    private final String previousLocationId;

    public ItemPositionChangedEvent(String itemId, String locationId, Double progress, String previousLocationId) {
        this(itemId, locationId, progress, previousLocationId, null);
    }

    @JsonCreator
    public ItemPositionChangedEvent(
            @JsonProperty("entityId") String itemId,
            @JsonProperty("locationId") String locationId,
            @JsonProperty("progress") Double progress,
            @JsonProperty("previousLocationId") String previousLocationId,
            @JsonProperty("timestamp") Instant timestamp) {

        super(itemId, "ITEM_POSITION_CHANGED", timestamp);
        this.locationId = locationId;
        this.progress = progress;
        this.previousLocationId = previousLocationId;
    }

    public ItemPositionChangedEvent(String itemId, String locationId, Double progress, Instant timestamp,
            String previousLocationId) {
        super(itemId, "ITEM_POSITION_CHANGED", timestamp);
        this.locationId = locationId;
        this.progress = progress;
        this.previousLocationId = previousLocationId;
    }
}