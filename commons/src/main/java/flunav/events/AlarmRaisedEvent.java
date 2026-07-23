package flunav.events;

import java.time.Instant;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import flunav.types.AlarmSeverity;
import lombok.Getter;

@Getter
public class AlarmRaisedEvent extends EntityEvent {
    private final String alarmId;
    private final String conveyorId;
    private final AlarmSeverity severity;
    private final String typology;
    private final boolean stopsConveyor;

    public AlarmRaisedEvent(String alarmId, String conveyorId, AlarmSeverity severity, String typology,
            boolean stopsConveyor) {
        this(alarmId, conveyorId, severity, typology, stopsConveyor, null);
    }

    @JsonCreator
    public AlarmRaisedEvent(
            @JsonProperty("alarmId") String alarmId,
            @JsonProperty("conveyorId") String conveyorId,
            @JsonProperty("severity") AlarmSeverity severity,
            @JsonProperty("typology") String typology,
            @JsonProperty("stopsConveyor") boolean stopsConveyor,
            @JsonProperty("timestamp") Instant timestamp) {
        super(conveyorId, "ALARM_RAISED", timestamp);
        this.alarmId = requireText(alarmId, "alarmId");
        this.conveyorId = requireText(conveyorId, "conveyorId");
        this.severity = Objects.requireNonNull(severity, "severity is required");
        this.typology = requireText(typology, "typology");
        this.stopsConveyor = stopsConveyor;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
