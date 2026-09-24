package flunav.events;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Getter;

@Getter
public class ItemActivatedEvent extends EntityEvent {
    @JsonCreator
    public ItemActivatedEvent(
            @JsonProperty("itemId") @JsonAlias("entityId") String itemId,
            @JsonProperty("timestamp") Instant timestamp) {
        super(itemId, "ITEM_ACTIVATED", timestamp);
    }

    public ItemActivatedEvent(String itemId) {
        this(itemId, null);
    }
}
