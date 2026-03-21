package flunav.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Getter;

import java.time.Instant;

/**
 * Event triggered when an item's position changes within the system.
 * The system tracks the item's location (node or edge) and its progress along
 * it.
 */
@Getter
public class ItemPositionChangedEvent extends EntityEvent {
    private final String locationId;
    private final Double progress;

    /**
     * Primary constructor for ItemPositionChangedEvent.
     */
    public ItemPositionChangedEvent(String itemId, String locationId, Double progress) {
        this(itemId, locationId, progress, (Instant) null);
    }

    /**
     * JSON-friendly constructor used by Jackson for deserialization.
     */
    @JsonCreator
    public ItemPositionChangedEvent(
            @JsonProperty("entityId") String itemId,
            @JsonProperty("locationId") String locationId,
            @JsonProperty("progress") Double progress,
            @JsonProperty("timestamp") Instant timestamp) {

        super(itemId, "ITEM_POSITION_CHANGED", timestamp);
        this.locationId = locationId;
        this.progress = progress;
    }
}