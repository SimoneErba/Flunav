package flunav.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Getter;

import java.time.Instant;
import java.util.List;

@Getter
public class MapDestinationsEvent extends DomainEvent {
    private final String fieldName;
    private final List<DestinationMappingRecord> mappings;

    @JsonCreator
    public MapDestinationsEvent(
            @JsonProperty("fieldName") String fieldName,
            @JsonProperty("mappings") List<DestinationMappingRecord> mappings,
            @JsonProperty("timestamp") Instant timestamp) {
        super("MAP_DESTINATIONS", timestamp);
        this.fieldName = fieldName;
        this.mappings = mappings;
    }

    public MapDestinationsEvent(
            String fieldName,
            List<DestinationMappingRecord> mappings) {
        this(fieldName, mappings, null);
    }
}
