package flunav.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

import java.util.List;

@Getter
public class DestinationExitMappingRecord {
    private final String destination;
    private final List<String> exits;

    @JsonCreator
    public DestinationExitMappingRecord(
            @JsonProperty("destination") String destination,
            @JsonProperty("exits") List<String> exits) {
        this.destination = destination;
        this.exits = exits;
    }
}
