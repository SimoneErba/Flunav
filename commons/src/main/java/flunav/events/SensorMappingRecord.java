package flunav.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Getter;

/** Immutable mapping from an external sensor identifier to a conveyor position. */
@Getter
public class SensorMappingRecord {
    private final String sensorName;
    private final String conveyorId;
    private final Double progress;

    @JsonCreator
    public SensorMappingRecord(
            @JsonProperty("sensorName") String sensorName,
            @JsonProperty("conveyorId") String conveyorId,
            @JsonProperty("progress") Double progress) {
        this.sensorName = sensorName;
        this.conveyorId = conveyorId;
        this.progress = progress;
    }
}
