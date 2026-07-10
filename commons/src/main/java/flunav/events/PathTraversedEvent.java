package flunav.events;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import flunav.types.PositionType;

import java.time.Instant;
import java.util.List;

import lombok.Getter;

@Getter
public class PathTraversedEvent extends EntityEvent {
    private final String previousPositionId;
    private final PositionType previousPositionType;
    private final String newPositionId;
    private final PositionType newPositionType;
    private final List<String> path;

    public PathTraversedEvent(
            String itemId,
            String previousPositionId,
            PositionType previousPositionType,
            String newPositionId,
            PositionType newPositionType,
            List<String> path) {
        this(itemId, previousPositionId, previousPositionType, newPositionId, newPositionType, path, null);
    }

    @JsonCreator
    public PathTraversedEvent(
            @JsonProperty("itemId") @JsonAlias("entityId") String itemId,
            @JsonProperty("previousPositionId") String previousPositionId,
            @JsonProperty("previousPositionType") PositionType previousPositionType,
            @JsonProperty("newPositionId") String newPositionId,
            @JsonProperty("newPositionType") PositionType newPositionType,
            @JsonProperty("path") List<String> path,
            @JsonProperty("timestamp") Instant timestamp) {
        super(itemId, "PATH_TRAVERSED", timestamp);
        this.previousPositionId = previousPositionId;
        this.previousPositionType = previousPositionType;
        this.newPositionId = newPositionId;
        this.newPositionType = newPositionType;
        this.path = path != null ? List.copyOf(path) : List.of();
    }
}
