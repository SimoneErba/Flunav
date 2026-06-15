package flunav.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

import java.time.Instant;

@Getter
public class ItemRoutingDecisionRequestedEvent extends EntityEvent {
    private final String decisionPointId;

    public ItemRoutingDecisionRequestedEvent(String itemId, String decisionPointId) {
        this(itemId, decisionPointId, null);
    }

    @JsonCreator
    public ItemRoutingDecisionRequestedEvent(
            @JsonProperty("entityId") String itemId,
            @JsonProperty("decisionPointId") String decisionPointId,
            @JsonProperty("timestamp") Instant timestamp) {
        super(itemId, "ITEM_ROUTING_DECISION_REQUESTED", timestamp);
        this.decisionPointId = decisionPointId;
    }
}
