package flunav.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import flunav.types.PositionType;

import lombok.Getter;
import java.util.List;

@Getter
public class PathTraversedEvent extends EntityEvent {
    private final String previousPositionId;
    private final PositionType previousPositionType;
    private final String newPositionId;
    private final PositionType newPositionType;
    private final List<String> path;

    @JsonCreator
    public PathTraversedEvent(
            @JsonProperty("itemId") String itemId,
            @JsonProperty("previousPositionId") String previousPositionId,
            @JsonProperty("previousPositionType") PositionType previousPositionType,
            @JsonProperty("newPositionId") String newPositionId,
            @JsonProperty("newPositionType") PositionType newPositionType,
            @JsonProperty("path") List<String> path) {
        super(itemId, "PATH_TRAVERSED");
        this.previousPositionId = previousPositionId;
        this.previousPositionType = previousPositionType;
        this.newPositionId = newPositionId;
        this.newPositionType = newPositionType;
        this.path = path;
    }
}
