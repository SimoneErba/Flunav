package flunav.types;

import java.util.stream.Stream;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum DataType {
    STRING("STRING"),
    NUMBER("NUMBER"),
    BOOLEAN("BOOLEAN"),
    DATETIME("DATETIME");

    private final String value;

    DataType(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static DataType fromString(String value) {
        if (value == null || value.isBlank()) {
            return STRING;
        }
        return Stream.of(DataType.values())
                .filter(type -> type.value.equalsIgnoreCase(value))
                .findFirst()
                .orElse(STRING);
    }
}
