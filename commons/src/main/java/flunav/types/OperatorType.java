package flunav.types;

import java.util.stream.Stream;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum OperatorType {
    EQUAL("EQUAL"),
    LESSER("LESSER"),
    GREATER("GREATER");

    private final String value;

    OperatorType(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static OperatorType fromString(String value) {
        if (value == null || value.isBlank()) {
            return EQUAL;
        }
        return Stream.of(OperatorType.values())
                .filter(type -> type.value.equalsIgnoreCase(value))
                .findFirst()
                .orElse(EQUAL);
    }
}
