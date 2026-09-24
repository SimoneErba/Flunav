package flunav.events;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Getter;

@Getter
public class ItemDeactivatedEvent extends EntityEvent {

    @JsonCreator
    public ItemDeactivatedEvent(
            @JsonProperty("itemId") @JsonAlias("entityId") String itemId,
            @JsonProperty("timestamp") Instant timestamp) {
        super(itemId, "ITEM_DEACTIVATED", timestamp);
    }

    public ItemDeactivatedEvent(String itemId) {
        this(itemId, null);
    }
}
