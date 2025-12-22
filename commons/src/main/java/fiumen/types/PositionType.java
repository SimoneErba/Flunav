package fiumen.types;

import java.util.stream.Stream;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum PositionType {
    LOCATION("LOCATION"),
    CONVEYOR("CONVEYOR");

    private final String value;

    PositionType(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static PositionType fromString(String value) {
        if (value == null || value.isBlank()) {
            return LOCATION;
        }
        return Stream.of(PositionType.values())
                .filter(type -> type.value.equalsIgnoreCase(value))
                .findFirst()
                .orElse(LOCATION);
    }
}
