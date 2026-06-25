package flunav.events;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Getter;

@Getter
public class ItemPriorityUpdatedEvent extends EntityEvent {
    private final Double priority;

    public ItemPriorityUpdatedEvent(String itemId, Double priority) {
        this(itemId, priority, null);
    }

    @JsonCreator
    public ItemPriorityUpdatedEvent(
            @JsonProperty("entityId") String itemId,
            @JsonProperty("priority") Double priority,
            @JsonProperty("timestamp") Instant timestamp) {
        super(itemId, "ITEM_PRIORITY_UPDATED", timestamp);
        this.priority = priority;
    }
}
