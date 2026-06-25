package flunav.types;

import java.util.stream.Stream;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum OperatorType {
    EQUAL("EQUAL"),
    LESSER("LESSER"),
    LESSER_OR_EQUAL("LESSER_OR_EQUAL"),
    GREATER("GREATER"),
    GREATER_OR_EQUAL("GREATER_OR_EQUAL");

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
        String normalized = value.trim().replace('-', '_').toUpperCase();
        if ("EQUALS".equals(normalized) || "EQ".equals(normalized)) {
            return EQUAL;
        }
        if ("GREATER_THAN".equals(normalized) || "GT".equals(normalized)) {
            return GREATER;
        }
        if ("GREATER_THAN_OR_EQUAL".equals(normalized) || "GREATER_EQUAL".equals(normalized)
                || "GTE".equals(normalized) || "GE".equals(normalized)) {
            return GREATER_OR_EQUAL;
        }
        if ("LESS_THAN".equals(normalized) || "LESSER_THAN".equals(normalized) || "LT".equals(normalized)) {
            return LESSER;
        }
        if ("LESS_THAN_OR_EQUAL".equals(normalized) || "LESSER_THAN_OR_EQUAL".equals(normalized)
                || "LESSER_EQUAL".equals(normalized) || "LTE".equals(normalized) || "LE".equals(normalized)) {
            return LESSER_OR_EQUAL;
        }
        return Stream.of(OperatorType.values())
                .filter(type -> type.value.equalsIgnoreCase(value.trim()))
                .findFirst()
                .orElse(EQUAL);
    }
}
