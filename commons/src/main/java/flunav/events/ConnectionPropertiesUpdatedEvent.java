package flunav.events;

import lombok.Getter;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

@Getter
public class ConnectionPropertiesUpdatedEvent extends EntityEvent {
    private final Map<String, Object> updatedProperties;

    @JsonCreator
    public ConnectionPropertiesUpdatedEvent(@JsonProperty("connectionId") String locationId,
            @JsonProperty("updatedProperties") Map<String, Object> updatedProperties) {
        super(locationId, "CONNECTION_PROPERTIES_UPDATED");
        this.updatedProperties = updatedProperties;
    }
}