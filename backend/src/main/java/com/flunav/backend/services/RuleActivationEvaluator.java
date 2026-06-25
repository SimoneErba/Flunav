package com.flunav.backend.services;

import flunav.types.DataType;
import flunav.types.OperatorType;

import java.time.Instant;
import java.util.Map;

final class RuleActivationEvaluator {

    private RuleActivationEvaluator() {
    }

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

    private static Object findValue(Map<String, Object> values, String fieldName) {
        if (values == null) {
            return null;
        }
        return values.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(fieldName))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }

    private static boolean containsField(Map<String, Object> values, String fieldName) {
        return values != null && values.keySet().stream()
                .anyMatch(key -> key != null && key.equalsIgnoreCase(fieldName));
    }

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
