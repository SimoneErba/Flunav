package flunav.events;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import flunav.types.ConveyorType;
import lombok.Getter;

@Getter
public class ConnectionCreatedEvent extends EntityEvent {

    private final String sourceId;
    private final String targetId;
    private final Double length;
    private final Double speed;
    private final Double minDistance;
    private final Long timeToTraverseMs;
    private final Boolean mainPath;
    private final String connectionId;
    private final String name;
    private final Boolean isActive;
    private final Integer capacity;
    private final ConveyorType type;

    private final Map<String, Object> properties;

    @JsonCreator
    public ConnectionCreatedEvent(
            @JsonProperty("connectionId") String connectionId,
            @JsonProperty("sourceId") String sourceId,
            @JsonProperty("targetId") String targetId,
            @JsonProperty("length") Double length,
            @JsonProperty("speed") Double speed,
            @JsonProperty("minDistance") Double minDistance,
            @JsonProperty("timeToTraverseMs") Long timeToTraverseMs,
            @JsonProperty("mainPath") Boolean mainPath,
            @JsonProperty("name") String name,
            @JsonProperty("isActive") Boolean isActive,
            @JsonProperty("type") ConveyorType type,
            @JsonProperty("capacity") Integer capacity,
            @JsonProperty("properties") Map<String, Object> properties) {
        super(connectionId, "CONNECTION_CREATED");
        this.connectionId = connectionId;
        this.sourceId = sourceId;
        this.targetId = targetId;
        this.length = (length != null) ? length : 1.0;
        this.speed = (speed != null) ? speed : 1.0;
        this.timeToTraverseMs = (timeToTraverseMs != null) ? timeToTraverseMs : 1000L;
        this.mainPath = (mainPath != null) ? mainPath : false;
        this.name = (name != null) ? name : "";
        this.isActive = (isActive != null) ? isActive : true;
        this.capacity = capacity;
        this.type = type != null ? type : ConveyorType.BELT;
        this.properties = properties != null ? properties : Map.of();
        this.minDistance = minDistance != null ? minDistance
                : this.type == ConveyorType.STAGING ? 0.1 : null;
    }
}
