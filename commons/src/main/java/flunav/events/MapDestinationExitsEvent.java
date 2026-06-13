package flunav.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

import java.time.Instant;
import java.util.List;

@Getter
public class MapDestinationExitsEvent extends DomainEvent {
    private final List<DestinationExitMappingRecord> mappings;

    @JsonCreator
    public MapDestinationExitsEvent(
            @JsonProperty("mappings") List<DestinationExitMappingRecord> mappings,
            @JsonProperty("timestamp") Instant timestamp) {
        super("MAP_DESTINATION_EXITS", timestamp);
        this.mappings = mappings;
    }

    public MapDestinationExitsEvent(List<DestinationExitMappingRecord> mappings) {
        this(mappings, null);
    }
}
