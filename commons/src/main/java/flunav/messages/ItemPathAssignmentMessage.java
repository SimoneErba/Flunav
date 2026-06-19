package flunav.messages;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

import java.time.Instant;
import java.util.List;

@Getter
public class ItemPathAssignmentMessage {
    public static final String MESSAGE_TYPE = "ITEM_PATH_ASSIGNED";

    private final String messageType;
    private final String itemId;
    private final String finalDestinationId;
    private final List<String> path;
    private final Instant timestamp;

    public ItemPathAssignmentMessage(String itemId, String finalDestinationId, List<String> path, Instant timestamp) {
        this(MESSAGE_TYPE, itemId, finalDestinationId, path, timestamp);
    }

    @JsonCreator
    public ItemPathAssignmentMessage(
            @JsonProperty("messageType") String messageType,
            @JsonProperty("itemId") String itemId,
            @JsonProperty("finalDestinationId") String finalDestinationId,
            @JsonProperty("path") List<String> path,
            @JsonProperty("timestamp") Instant timestamp) {
        this.messageType = messageType == null ? MESSAGE_TYPE : messageType;
        this.itemId = itemId;
        this.finalDestinationId = finalDestinationId;
        this.path = path == null ? List.of() : List.copyOf(path);
        this.timestamp = timestamp;
    }
}
