package flunav.events;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Getter;

/** Replaces the sensor-to-conveyor mapping table in the active context. */
@Getter
public class MapSensorMappingsEvent extends DomainEvent {
    private final List<SensorMappingRecord> mappings;

    @JsonCreator
    public MapSensorMappingsEvent(
            @JsonProperty("mappings") List<SensorMappingRecord> mappings,
            @JsonProperty("timestamp") Instant timestamp) {
        super("MAP_SENSOR_MAPPINGS", timestamp);
        this.mappings = mappings;
    }

    public MapSensorMappingsEvent(List<SensorMappingRecord> mappings) {
        this(mappings, null);
    }
}
