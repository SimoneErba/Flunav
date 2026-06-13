package flunav.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

import java.time.Instant;
import java.util.List;

@Getter
public class ItemPathChangedEvent extends EntityEvent {
    private final List<String> path;
    private final boolean emptyPath;

    public ItemPathChangedEvent(String itemId, List<String> path) {
        this(itemId, path, null);
    }

    public ItemPathChangedEvent(
            String itemId,
            List<String> path,
            Instant timestamp) {
        this(itemId, path, path != null && path.isEmpty(), timestamp);
    }

    @JsonCreator
    public ItemPathChangedEvent(
            @JsonProperty("entityId") String itemId,
            @JsonProperty("path") List<String> path,
            @JsonProperty("emptyPath") Boolean emptyPath,
            @JsonProperty("timestamp") Instant timestamp) {
        super(itemId, "ITEM_PATH_CHANGED", timestamp);
        List<String> restoredPath = path;
        if (restoredPath == null && Boolean.TRUE.equals(emptyPath)) {
            restoredPath = List.of();
        }
        if (restoredPath == null) {
            throw new IllegalArgumentException("Path is required.");
        }
        this.path = List.copyOf(restoredPath);
        this.emptyPath = this.path.isEmpty();
    }
}
