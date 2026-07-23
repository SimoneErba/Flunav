package flunav.types;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public final class ActiveAlarm {
    private final String alarmId;
    private final String conveyorId;
    private final AlarmSeverity severity;
    private final String typology;
    private final boolean stopsConveyor;
    private final Instant raisedAt;

    @JsonCreator
    public ActiveAlarm(
            @JsonProperty("alarmId") String alarmId,
            @JsonProperty("conveyorId") String conveyorId,
            @JsonProperty("severity") AlarmSeverity severity,
            @JsonProperty("typology") String typology,
            @JsonProperty("stopsConveyor") boolean stopsConveyor,
            @JsonProperty("raisedAt") Instant raisedAt) {
        this.alarmId = alarmId;
        this.conveyorId = conveyorId;
        this.severity = severity;
        this.typology = typology;
        this.stopsConveyor = stopsConveyor;
        this.raisedAt = raisedAt;
    }

    public String getAlarmId() {
        return alarmId;
    }

    public String getConveyorId() {
        return conveyorId;
    }

    public AlarmSeverity getSeverity() {
        return severity;
    }

    public String getTypology() {
        return typology;
    }

    public boolean isStopsConveyor() {
        return stopsConveyor;
    }

    public Instant getRaisedAt() {
        return raisedAt;
    }
}
