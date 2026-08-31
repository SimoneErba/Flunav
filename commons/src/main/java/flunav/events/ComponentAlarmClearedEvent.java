package flunav.events;

import java.time.Instant;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import flunav.types.ComponentType;
import lombok.Getter;

@Getter
public final class ComponentAlarmClearedEvent extends EntityEvent {
    private final String alarmId;
    private final String componentId;
    private final ComponentType componentType;

    @JsonCreator
    public ComponentAlarmClearedEvent(
            @JsonProperty("alarmId") String alarmId,
            @JsonProperty("componentId") String componentId,
            @JsonProperty("componentType") ComponentType componentType,
            @JsonProperty("timestamp") Instant timestamp) {
        super(requireText(componentId, "componentId"), "COMPONENT_ALARM_CLEARED", timestamp);
        this.alarmId = requireText(alarmId, "alarmId");
        this.componentId = componentId;
        this.componentType = Objects.requireNonNull(componentType, "componentType is required");
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
