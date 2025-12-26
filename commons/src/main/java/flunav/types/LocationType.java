package flunav.types;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.stream.Stream;

/**
 * Defines the specific functional types of locations within the system.
 * Using an enum provides type safety, code clarity, and centralizes the logic.
 */
public enum LocationType {

    // Represents a logical point where paths diverge or converge.
    // Typically has no physical dimensions (length/speed).
    JUNCTION("JUNCTION"),

    // Represents a final destination point where items exit the active system.
    CHUTE("CHUTE"),

    // Represents a track with a finite capacity, used for buffering items.
    ACCUMULATION("ACCUMULATION"),

    // Represents a physical, non-motorized path (e.g., a gravity slide).
    // Typically has length but no speed.
    ROAD("ROAD"),

    // A generic or undefined location type for flexibility.
    GENERIC("GENERIC");

    private final String value;

    LocationType(String value) {
        this.value = value;
    }

    /**
     * This method allows Jackson to correctly serialize the enum to its string
     * value.
     * When you return a Location in a JSON API, it will show "CONVEYOR" instead of
     * the enum name.
     */
    @JsonValue
    public String getValue() {
        return value;
    }

    /**
     * This method allows Jackson (and your own code) to safely create an enum from
     * a string.
     * It's case-insensitive and defaults to GENERIC if the string is unknown.
     *
     * @param value The string to convert (e.g., "conveyor", "CONVEYOR", or
     *              "Conveyor").
     * @return The corresponding LocationType, or GENERIC if no match is found.
     */
    @JsonCreator
    public static LocationType fromString(String value) {
        if (value == null || value.isBlank()) {
            return GENERIC;
        }
        return Stream.of(LocationType.values())
                .filter(type -> type.value.equalsIgnoreCase(value))
                .findFirst()
                .orElse(GENERIC);
    }
}
