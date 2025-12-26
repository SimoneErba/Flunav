package flunav.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Getter;

@Getter
public class ConnectionRemoveFromMainPath extends EntityEvent {

    @JsonCreator
    public ConnectionRemoveFromMainPath(@JsonProperty("locationId") String locationId) {
        super(locationId, "CONNECTION_REMOVE_MAIN_PATH");
    }
}