package flunav.events;

import java.time.Instant;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import flunav.types.AlarmSeverity;
import flunav.types.AlarmSource;
import flunav.types.ComponentType;
import lombok.Getter;

@Getter
public final class ComponentAlarmRaisedEvent extends EntityEvent {
    private final String alarmId;
    private final String findingId;
    private final String componentId;
    private final ComponentType componentType;
    private final String typology;
    private final AlarmSource source;
    private final AlarmSeverity severity;
    private final boolean stopsComponent;

    @JsonCreator
    public ComponentAlarmRaisedEvent(
            @JsonProperty("alarmId") String alarmId,
            @JsonProperty("findingId") String findingId,
            @JsonProperty("componentId") String componentId,
            @JsonProperty("componentType") ComponentType componentType,
            @JsonProperty("typology") String typology,
            @JsonProperty("source") AlarmSource source,
            @JsonProperty("severity") AlarmSeverity severity,
            @JsonProperty("stopsComponent") boolean stopsComponent,
            @JsonProperty("timestamp") Instant timestamp) {
        super(requireText(componentId, "componentId"), "COMPONENT_ALARM_RAISED", timestamp);
        this.alarmId = requireText(alarmId, "alarmId");
        this.findingId = findingId;
        this.componentId = componentId;
        this.componentType = Objects.requireNonNull(componentType, "componentType is required");
        this.typology = requireText(typology, "typology");
        this.source = Objects.requireNonNull(source, "source is required");
        this.severity = Objects.requireNonNull(severity, "severity is required");
        this.stopsComponent = stopsComponent;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
