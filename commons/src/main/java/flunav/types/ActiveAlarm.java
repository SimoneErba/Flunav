package flunav.types;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonAlias;

public final class ActiveAlarm {
    private final String alarmId;
    private final String conveyorId;
    private final AlarmSeverity severity;
    private final String typology;
    private final boolean stopsConveyor;
    private final Instant raisedAt;
    private final String findingId;
    private final ComponentType componentType;
    private final AlarmSource source;

    public ActiveAlarm(
            String alarmId,
            String conveyorId,
            AlarmSeverity severity,
            String typology,
            boolean stopsConveyor,
            Instant raisedAt) {
        this(alarmId, conveyorId, null, ComponentType.CONVEYOR, severity, typology,
                AlarmSource.MANUAL, stopsConveyor, raisedAt);
    }

    @JsonCreator
    public ActiveAlarm(
            @JsonProperty("alarmId") String alarmId,
            @JsonProperty("componentId") @JsonAlias("conveyorId") String componentId,
            @JsonProperty("findingId") String findingId,
            @JsonProperty("componentType") ComponentType componentType,
            @JsonProperty("severity") AlarmSeverity severity,
            @JsonProperty("typology") String typology,
            @JsonProperty("source") AlarmSource source,
            @JsonProperty("stopsComponent") @JsonAlias("stopsConveyor") boolean stopsComponent,
            @JsonProperty("raisedAt") Instant raisedAt) {
        this.alarmId = alarmId;
        this.conveyorId = componentId;
        this.severity = severity;
        this.typology = typology;
        this.stopsConveyor = stopsComponent;
        this.raisedAt = raisedAt;
        this.findingId = findingId;
        this.componentType = componentType != null ? componentType : ComponentType.CONVEYOR;
        this.source = source != null ? source : AlarmSource.MANUAL;
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

    public String getComponentId() {
        return conveyorId;
    }

    public String getFindingId() {
        return findingId;
    }

    public ComponentType getComponentType() {
        return componentType;
    }

    public AlarmSource getSource() {
        return source;
    }

    public boolean isStopsComponent() {
        return stopsConveyor;
    }
}
