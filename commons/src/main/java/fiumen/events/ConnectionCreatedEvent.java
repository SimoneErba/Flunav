package fiumen.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

@Getter
public class ConnectionCreatedEvent extends EntityEvent {

    private final String sourceId;
    private final String targetId;
    private final Double length;
    private final Double speed;
    private final Long timeToTraverseMs;
    private final Boolean isMainPath;
    private final String connectionId;
    private final String name;
    private final Boolean isActive;

    @JsonCreator
    public ConnectionCreatedEvent(
            @JsonProperty("connectionId") String connectionId,
            @JsonProperty("sourceId") String sourceId,
            @JsonProperty("targetId") String targetId,
            @JsonProperty("length") Double length,
            @JsonProperty("speed") Double speed,
            @JsonProperty("timeToTraverseMs") Long timeToTraverseMs,
            @JsonProperty("isMainPath") Boolean isMainPath,
            @JsonProperty("name") String name,
            @JsonProperty("isActive") Boolean isActive) {
        super(connectionId, "CONNECTION_CREATED");
        this.connectionId = connectionId;
        this.sourceId = sourceId;
        this.targetId = targetId;
        this.length = (length != null) ? length : 1.0;
        this.speed = (speed != null) ? speed : 1.0;
        this.timeToTraverseMs = (timeToTraverseMs != null) ? timeToTraverseMs : 1000L;
        this.isMainPath = (isMainPath != null) ? isMainPath : false;
        this.name = (name != null) ? name : "";
        this.isActive = (isActive != null) ? isActive : true;
    }
}