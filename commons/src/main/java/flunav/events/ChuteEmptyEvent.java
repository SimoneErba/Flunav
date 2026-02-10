package flunav.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

import java.time.Instant;

@Getter
public class ChuteEmptyEvent extends EntityEvent {
    public ChuteEmptyEvent(String chuteId) {
        this(chuteId, null);
    }

    @JsonCreator
    public ChuteEmptyEvent(
            @JsonProperty("entityId") String chuteId,
            @JsonProperty("timestamp") Instant timestamp) {
        super(chuteId, "CHUTE_EMPTY", timestamp);
    }
}
