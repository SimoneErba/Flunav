package com.flunav.backend.services;

import flunav.types.DataType;
import flunav.types.OperatorType;

import java.time.Instant;
import java.util.Map;

final class RuleActivationEvaluator {

    private RuleActivationEvaluator() {
    }

    /**
     * Evaluates a rule field against root fields before custom properties.
     * This keeps first-class domain fields such as priority and active status from
     * being overridden by a property with the same name.
     */
    static boolean isActive(Map<String, Object> rootFields, Map<String, Object> properties, String fieldName,
            DataType dataType,
            OperatorType operator, Object ruleValue) {
        if (fieldName == null) {
            return false;
        }

        boolean rootContainsField = containsField(rootFields, fieldName);
        Object propValue = rootContainsField ? findValue(rootFields, fieldName) : findValue(properties, fieldName);

        return isActive(propValue, dataType, operator, ruleValue);
    }

    static boolean isActive(Map<String, Object> properties, String fieldName, DataType dataType,
            OperatorType operator, Object ruleValue) {
        return isActive(Map.of(), properties, fieldName, dataType, operator, ruleValue);
    }

    /**
     * Finds a field case-insensitively because rule configuration is user-facing
     * while stored property names may come from external systems.
     */
    private static Object findValue(Map<String, Object> values, String fieldName) {
        if (values == null) {
            return null;
        }
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            String key = entry.getKey();
            if (key != null && key.equalsIgnoreCase(fieldName)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * Checks whether a case-insensitive root field exists even when its value is
     * null, so null root values still intentionally shadow custom properties.
     */
    private static boolean containsField(Map<String, Object> values, String fieldName) {
        return values != null && values.keySet().stream()
                .anyMatch(key -> key != null && key.equalsIgnoreCase(fieldName));
    }

    /**
     * Applies the typed comparison for one field and treats parse failures as a
     * non-match. Display-rule reads should remain resilient to malformed external
     * property values.
     */
    static boolean isActive(Object propValue, DataType dataType, OperatorType operator, Object ruleValue) {
        if (propValue == null || dataType == null || operator == null || ruleValue == null) {
            return false;
        }

        try {
            return switch (dataType) {
                case STRING -> propValue.toString().equals(ruleValue.toString());
                case BOOLEAN -> Boolean.parseBoolean(propValue.toString()) == Boolean.parseBoolean(ruleValue.toString());
                case NUMBER -> compareNumbers(propValue, operator, ruleValue);
                case DATETIME -> compareInstants(propValue, operator, ruleValue);
            };
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Compares numeric values after parsing the stored field and configured value.
     * Validation should catch invalid rule values, while malformed property values
     * are handled by the caller as a non-match.
     */
    private static boolean compareNumbers(Object propValue, OperatorType operator, Object ruleValue) {
        double propNum = Double.parseDouble(propValue.toString());
        double ruleNum = Double.parseDouble(ruleValue.toString());
        return switch (operator) {
            case EQUAL -> propNum == ruleNum;
            case GREATER -> propNum > ruleNum;
            case GREATER_OR_EQUAL -> propNum >= ruleNum;
            case LESSER -> propNum < ruleNum;
            case LESSER_OR_EQUAL -> propNum <= ruleNum;
        };
    }

    /**
     * Compares timestamp values using Instant ordering.
     * Inclusive operators are expressed with isBefore/isAfter inversions so the
     * evaluator handles exact boundary matches consistently.
     */
    private static boolean compareInstants(Object propValue, OperatorType operator, Object ruleValue) {
        Instant propTime = Instant.parse(propValue.toString());
        Instant ruleTime = Instant.parse(ruleValue.toString());
        return switch (operator) {
            case EQUAL -> propTime.equals(ruleTime);
            case GREATER -> propTime.isAfter(ruleTime);
            case GREATER_OR_EQUAL -> !propTime.isBefore(ruleTime);
            case LESSER -> propTime.isBefore(ruleTime);
            case LESSER_OR_EQUAL -> !propTime.isAfter(ruleTime);
        };
    }
}
