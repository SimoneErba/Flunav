package fiumen.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Getter;

@Getter
public class ConnectionDeletedEvent extends DomainEvent {
    private final String sourceLocationId;
    private final String targetLocationId;

    @JsonCreator
    public ConnectionDeletedEvent(@JsonProperty("sourceLocationId") String sourceLocationId, @JsonProperty("targetLocationId") String targetLocationId) {
        super("CONNECTION_DELETED");
        this.targetLocationId = targetLocationId;
        this.sourceLocationId = sourceLocationId;
    }
} 