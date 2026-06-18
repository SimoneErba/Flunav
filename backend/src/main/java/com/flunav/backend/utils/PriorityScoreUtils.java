package com.flunav.backend.utils;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class PriorityScoreUtils {
    private static final Set<String> FULL_PRIORITY_LABELS = Set.of("HIGH", "EXPEDITE", "URGENT", "CRITICAL");

    private PriorityScoreUtils() {
    }

    /**
     * Converts loose item properties into the normalized priority score used by
     * routing. Numeric values support fractional urgency, while known labels map
     * to full priority for integrations that send textual urgency.
     */
    public static double priorityScore(Map<String, Object> properties) {
        if (properties == null) {
            return 0.0;
        }
        Object value = properties.entrySet().stream()
                .filter(entry -> "priority".equalsIgnoreCase(entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
        if (value == null) {
            return 0.0;
        }
        if (value instanceof Number number) {
            return normalize(number.doubleValue());
        }

        String priority = value.toString().trim();
        if (priority.isEmpty()) {
            return 0.0;
        }
        try {
            return normalize(Double.parseDouble(priority));
        } catch (NumberFormatException ignored) {
            return FULL_PRIORITY_LABELS.contains(priority.toUpperCase(Locale.ROOT)) ? 1.0 : 0.0;
        }
    }

    /**
     * Clamps and rounds priority so route scoring is stable across numeric input
     * formats and cannot exceed the capacity model's expected range.
     */
    private static double normalize(double value) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        double clamped = Math.max(0.0, Math.min(1.0, value));
        return Math.round(clamped * 100.0) / 100.0;
    }
}
