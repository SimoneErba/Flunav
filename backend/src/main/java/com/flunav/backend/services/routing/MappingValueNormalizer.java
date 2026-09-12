package com.flunav.backend.services.routing;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * Shared normalization rules for ordered values in routing mapping tables.
 * Event order is significant, while repeated values carry no additional meaning.
 */
public final class MappingValueNormalizer {

    private MappingValueNormalizer() {
    }

    public static List<String> requiredOrderedValues(List<String> values, String fieldName) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " are required");
        }

        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(fieldName + " must contain nonblank values");
            }
            normalized.add(value.trim());
        }
        return List.copyOf(normalized);
    }
}
