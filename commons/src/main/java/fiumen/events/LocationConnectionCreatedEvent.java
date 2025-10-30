package fiumen.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Getter;

@Getter
public class LocationConnectionCreatedEvent extends EntityEvent {

    private String location2Id;

    @JsonCreator
    public LocationConnectionCreatedEvent( @JsonProperty("location1Id") String location1Id,  @JsonProperty("location2Id") String location2Id) {
        super(location1Id, "LOCATION_CONNECTION_CREATED");
        this.location2Id = location2Id;
    }
}